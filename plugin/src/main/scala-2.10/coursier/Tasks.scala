package coursier

import java.io.{ OutputStreamWriter, File }
import java.nio.file.Files
import java.util.concurrent.Executors

import coursier.core.Publication
import coursier.ivy.IvyRepository
import coursier.Keys._
import coursier.Structure._
import coursier.util.{ Config, Print }
import org.apache.ivy.core.module.id.ModuleRevisionId

import sbt.{ UpdateReport, Classpaths, Resolver, Def }
import sbt.Configurations.{ Compile, Test }
import sbt.Keys._

import scala.collection.mutable
import scala.collection.JavaConverters._

import scalaz.{ \/-, -\/ }
import scalaz.concurrent.{ Task, Strategy }

object Tasks {

  def coursierResolversTask: Def.Initialize[sbt.Task[Seq[Resolver]]] =
    (
      externalResolvers,
      sbtPlugin,
      sbtResolver,
      bootResolvers,
      overrideBuildResolvers
    ).map { (extRes, isSbtPlugin, sbtRes, bootResOpt, overrideFlag) =>
      bootResOpt.filter(_ => overrideFlag).getOrElse {
        var resolvers = extRes
        if (isSbtPlugin)
          resolvers = Seq(
            sbtRes,
            Classpaths.sbtPluginReleases
          ) ++ resolvers
        resolvers
      }
    }

  def coursierProjectTask: Def.Initialize[sbt.Task[Project]] =
    (
      sbt.Keys.state,
      sbt.Keys.thisProjectRef
    ).flatMap { (state, projectRef) =>

      // should projectID.configurations be used instead?
      val configurations = ivyConfigurations.in(projectRef).get(state)

      val allDependenciesTask = allDependencies.in(projectRef).get(state)

      for {
        allDependencies <- allDependenciesTask
      } yield {

        FromSbt.project(
          projectID.in(projectRef).get(state),
          allDependencies,
          configurations.map { cfg => cfg.name -> cfg.extendsConfigs.map(_.name) }.toMap,
          scalaVersion.in(projectRef).get(state),
          scalaBinaryVersion.in(projectRef).get(state)
        )
      }
    }

  def coursierProjectsTask: Def.Initialize[sbt.Task[Seq[Project]]] =
    sbt.Keys.state.flatMap { state =>
      val projects = structure(state).allProjectRefs
      coursierProject.forAllProjects(state, projects).map(_.values.toVector)
    }

  def coursierPublicationsTask: Def.Initialize[sbt.Task[Seq[(String, Publication)]]] =
    (
      sbt.Keys.state,
      sbt.Keys.thisProjectRef,
      sbt.Keys.projectID,
      sbt.Keys.scalaVersion,
      sbt.Keys.scalaBinaryVersion,
      sbt.Keys.ivyConfigurations
    ).map { (state, projectRef, projId, sv, sbv, ivyConfs) =>

      val packageTasks = Seq(packageBin, packageSrc, packageDoc)
      val configs = Seq(Compile, Test)

      val sbtArtifacts =
        for {
          pkgTask <- packageTasks
          config <- configs
        } yield {
          val publish = publishArtifact.in(projectRef).in(pkgTask).in(config).getOrElse(state, false)
          if (publish)
            Option(artifact.in(projectRef).in(pkgTask).in(config).getOrElse(state, null))
              .map(config.name -> _)
          else
            None
        }

      def artifactPublication(artifact: sbt.Artifact) = {

        val name = FromSbt.sbtCrossVersionName(
          artifact.name,
          projId.crossVersion,
          sv,
          sbv
        )

        Publication(
          name,
          artifact.`type`,
          artifact.extension,
          artifact.classifier.getOrElse("")
        )
      }

      val sbtArtifactsPublication = sbtArtifacts.collect {
        case Some((config, artifact)) =>
          config -> artifactPublication(artifact)
      }

      val stdArtifactsSet = sbtArtifacts.flatMap(_.map { case (_, a) => a }.toSeq).toSet

      // Second-way of getting artifacts from SBT
      // No obvious way of getting the corresponding  publishArtifact  value for the ones
      // only here, it seems.
      val extraSbtArtifacts = Option(artifacts.in(projectRef).getOrElse(state, null))
        .toSeq
        .flatten
        .filterNot(stdArtifactsSet)

      // Seems that SBT does that - if an artifact has no configs,
      // it puts it in all of them. See for example what happens to
      // the standalone JAR artifact of the coursier cli module.
      def allConfigsIfEmpty(configs: Iterable[sbt.Configuration]): Iterable[sbt.Configuration] =
        if (configs.isEmpty) ivyConfs else configs

      val extraSbtArtifactsPublication = for {
        artifact <- extraSbtArtifacts
        config <- allConfigsIfEmpty(artifact.configurations) if config.isPublic
      } yield config.name -> artifactPublication(artifact)

      sbtArtifactsPublication ++ extraSbtArtifactsPublication
    }

  // FIXME More things should possibly be put here too (resolvers, etc.)
  private case class CacheKey(
    project: Project,
    repositories: Seq[Repository],
    resolution: Resolution,
    withClassifiers: Boolean,
    sbtClassifiers: Boolean
  )

  private val resolutionsCache = new mutable.HashMap[CacheKey, UpdateReport]

  private def forcedScalaModules(scalaVersion: String): Map[Module, String] =
    Map(
      Module("org.scala-lang", "scala-library") -> scalaVersion,
      Module("org.scala-lang", "scala-compiler") -> scalaVersion,
      Module("org.scala-lang", "scala-reflect") -> scalaVersion,
      Module("org.scala-lang", "scalap") -> scalaVersion
    )

  def updateTask(withClassifiers: Boolean, sbtClassifiers: Boolean = false) = Def.task {

    // SBT logging should be better than that most of the time...
    def errPrintln(s: String): Unit = scala.Console.err.println(s)

    def grouped[K, V](map: Seq[(K, V)]): Map[K, Seq[V]] =
      map.groupBy { case (k, _) => k }.map {
        case (k, l) =>
          k -> l.map { case (_, v) => v }
      }

    // let's update only one module at once, for a better output
    // Downloads are already parallel, no need to parallelize further anyway
    synchronized {

      lazy val cm = coursierSbtClassifiersModule.value

      val currentProject =
        if (sbtClassifiers)
          FromSbt.project(
            cm.id,
            cm.modules,
            cm.configurations.map(cfg => cfg.name -> cfg.extendsConfigs.map(_.name)).toMap,
            scalaVersion.value,
            scalaBinaryVersion.value
          )
        else {
          val proj = coursierProject.value
          val publications = coursierPublications.value
          proj.copy(publications = publications)
        }

      val ivySbt0 = ivySbt.value
      val ivyCacheManager = ivySbt0.withIvy(streams.value.log)(ivy =>
        ivy.getResolutionCacheManager
      )

      val ivyModule = ModuleRevisionId.newInstance(
        currentProject.module.organization,
        currentProject.module.name,
        currentProject.version,
        currentProject.module.attributes.asJava
      )
      val cacheIvyFile = ivyCacheManager.getResolvedIvyFileInCache(ivyModule)
      val cacheIvyPropertiesFile = ivyCacheManager.getResolvedIvyPropertiesInCache(ivyModule)

      val projects = coursierProjects.value

      val parallelDownloads = coursierParallelDownloads.value
      val checksums = coursierChecksums.value
      val artifactsChecksums = coursierArtifactsChecksums.value
      val maxIterations = coursierMaxIterations.value
      val cachePolicies = coursierCachePolicies.value
      val cache = coursierCache.value

      val sv = scalaVersion.value // is this always defined? (e.g. for Java only projects?)
      val sbv = scalaBinaryVersion.value

      val userForceVersions = dependencyOverrides.value.map(
        FromSbt.moduleVersion(_, sv, sbv)
      ).toMap

      val resolvers =
        if (sbtClassifiers)
          coursierSbtResolvers.value
        else
          coursierResolvers.value

      val verbosityLevel = coursierVerbosity.value


      val startRes = Resolution(
        currentProject.dependencies.map { case (_, dep) => dep }.toSet,
        filter = Some(dep => !dep.optional),
        forceVersions = userForceVersions ++ forcedScalaModules(sv) ++ projects.map(_.moduleVersion)
      )

      // required for publish to be fine, later on
      def writeIvyFiles() = {
        val printer = new scala.xml.PrettyPrinter(80, 2)

        val b = new StringBuilder
        b ++= """<?xml version="1.0" encoding="UTF-8"?>"""
        b += '\n'
        b ++= printer.format(MakeIvyXml(currentProject))
        cacheIvyFile.getParentFile.mkdirs()
        Files.write(cacheIvyFile.toPath, b.result().getBytes("UTF-8"))

        // Just writing an empty file here... Are these only used?
        cacheIvyPropertiesFile.getParentFile.mkdirs()
        Files.write(cacheIvyPropertiesFile.toPath, "".getBytes("UTF-8"))
      }

      if (verbosityLevel >= 2) {
        println("InterProjectRepository")
        for (p <- projects)
          println(s"  ${p.module}:${p.version}")
      }

      val globalPluginsRepo = IvyRepository(
        new File(sys.props("user.home") + "/.sbt/0.13/plugins/target/resolution-cache/").toURI.toString +
          "[organization]/[module](/scala_[scalaVersion])(/sbt_[sbtVersion])/[revision]/resolved.xml.[ext]",
        withChecksums = false,
        withSignatures = false,
        withArtifacts = false
      )

      val interProjectRepo = InterProjectRepository(projects)

      val ivyProperties = Map(
        "ivy.home" -> (new File(sys.props("user.home")).toURI.getPath + ".ivy2")
      ) ++ sys.props

      val repositories = Seq(globalPluginsRepo, interProjectRepo) ++ resolvers.flatMap(FromSbt.repository(_, ivyProperties))

      def report = {
        val pool = Executors.newFixedThreadPool(parallelDownloads, Strategy.DefaultDaemonThreadFactory)

        def createLogger() = new TermDisplay(new OutputStreamWriter(System.err))

        val resLogger = createLogger()

        val fetch = Fetch.from(
          repositories,
          Cache.fetch(cache, cachePolicies.head, checksums = checksums, logger = Some(resLogger), pool = pool),
          cachePolicies.tail.map(p =>
            Cache.fetch(cache, p, checksums = checksums, logger = Some(resLogger), pool = pool)
          ): _*
        )

        def depsRepr(deps: Seq[(String, Dependency)]) =
          deps.map { case (config, dep) =>
            s"${dep.module}:${dep.version}:$config->${dep.configuration}"
          }.sorted.distinct

        def depsRepr0(deps: Seq[Dependency]) =
          deps.map { dep =>
            s"${dep.module}:${dep.version}:${dep.configuration}"
          }.sorted.distinct

        if (verbosityLevel >= 1) {
          val repoReprs = repositories.map {
            case r: IvyRepository =>
              s"ivy:${r.pattern}"
            case r: InterProjectRepository =>
              "inter-project"
            case r: MavenRepository =>
              r.root
            case r =>
              // should not happen
              r.toString
          }

          errPrintln(s"Repositories:\n${repoReprs.map("  "+_).mkString("\n")}")
        }

        if (verbosityLevel >= 0)
          errPrintln(s"Resolving ${currentProject.module.organization}:${currentProject.module.name}:${currentProject.version}")
        if (verbosityLevel >= 1)
          for (depRepr <- depsRepr(currentProject.dependencies))
            errPrintln(s"  $depRepr")

        resLogger.init()

        val res = startRes
          .process
          .run(fetch, maxIterations)
          .attemptRun
          .leftMap(ex => throw new Exception(s"Exception during resolution", ex))
          .merge

        resLogger.stop()


        if (!res.isDone)
          throw new Exception(s"Maximum number of iteration of dependency resolution reached")

        if (res.conflicts.nonEmpty) {
          val projCache = res.projectCache.mapValues { case (_, p) => p }
          println(s"${res.conflicts.size} conflict(s):\n  ${Print.dependenciesUnknownConfigs(res.conflicts.toVector, projCache)}")
          throw new Exception(s"Conflict(s) in dependency resolution")
        }

        if (res.errors.nonEmpty) {
          println(s"\n${res.errors.size} error(s):")
          for ((dep, errs) <- res.errors) {
            println(s"  ${dep.module}:${dep.version}:\n${errs.map("    " + _.replace("\n", "    \n")).mkString("\n")}")
          }
          throw new Exception(s"Encountered ${res.errors.length} error(s) in dependency resolution")
        }

        val depsByConfig = grouped(currentProject.dependencies)

        val configs = {
          val configs0 = ivyConfigurations.value.map { config =>
            config.name -> config.extendsConfigs.map(_.name)
          }.toMap

          def allExtends(c: String) = {
            // possibly bad complexity
            def helper(current: Set[String]): Set[String] = {
              val newSet = current ++ current.flatMap(configs0.getOrElse(_, Nil))
              if ((newSet -- current).nonEmpty)
                helper(newSet)
              else
                newSet
            }

            helper(Set(c))
          }

          configs0.map {
            case (config, _) =>
              config -> allExtends(config)
          }
        }

        if (verbosityLevel >= 0)
          errPrintln("Resolution done")
        if (verbosityLevel >= 1) {
          val finalDeps = Config.dependenciesWithConfig(
            res,
            depsByConfig.map { case (k, l) => k -> l.toSet },
            configs
          )

          val projCache = res.projectCache.mapValues { case (_, p) => p }
          val repr = Print.dependenciesUnknownConfigs(finalDeps.toVector, projCache)
          println(repr.split('\n').map("  "+_).mkString("\n"))
        }

        val classifiers =
          if (withClassifiers)
            Some {
              if (sbtClassifiers)
                cm.classifiers
              else
                transitiveClassifiers.value
            }
          else
            None

        val allArtifacts =
          classifiers match {
            case None => res.artifacts
            case Some(cl) => res.classifiersArtifacts(cl)
          }

        val artifactsLogger = createLogger()

        val artifactFileOrErrorTasks = allArtifacts.toVector.map { a =>
          def f(p: CachePolicy) =
            Cache.file(
              a,
              cache,
              p,
              checksums = artifactsChecksums,
              logger = Some(artifactsLogger),
              pool = pool
            )

          cachePolicies.tail
            .foldLeft(f(cachePolicies.head))(_ orElse f(_))
            .run
            .map((a, _))
        }

        if (verbosityLevel >= 0)
          errPrintln(s"Fetching artifacts")

        artifactsLogger.init()

        val artifactFilesOrErrors = Task.gatherUnordered(artifactFileOrErrorTasks).attemptRun match {
          case -\/(ex) =>
            throw new Exception(s"Error while downloading / verifying artifacts", ex)
          case \/-(l) =>
            l.toMap
        }

        artifactsLogger.stop()

        if (verbosityLevel >= 0)
          errPrintln(s"Fetching artifacts: done")

        def artifactFileOpt(artifact: Artifact) = {
          val fileOrError = artifactFilesOrErrors.getOrElse(artifact, -\/("Not downloaded"))

          fileOrError match {
            case \/-(file) =>
              if (file.toString.contains("file:/"))
                throw new Exception(s"Wrong path: $file")
              Some(file)
            case -\/(err) =>
              errPrintln(s"${artifact.url}: $err")
              None
          }
        }

        writeIvyFiles()

        ToSbt.updateReport(
          depsByConfig,
          res,
          configs,
          classifiers,
          artifactFileOpt
        )
      }

      resolutionsCache.getOrElseUpdate(
        CacheKey(
          currentProject,
          repositories,
          startRes.copy(filter = None),
          withClassifiers,
          sbtClassifiers
        ),
        report
      )
    }
  }

}
