package coursier
package cli

import java.io.{ OutputStreamWriter, File }
import java.util.concurrent.Executors

import coursier.ivy.IvyRepository
import coursier.util.{Print, Parse}

import scalaz.{Failure, Success, \/-, -\/}
import scalaz.concurrent.{ Task, Strategy }

object Helper {
  def fileRepr(f: File) = f.toString

  def errPrintln(s: String) = Console.err.println(s)

  def mainClasses(cl: ClassLoader): Map[(String, String), String] = {
    import scala.collection.JavaConverters._

    val metaInfs = cl.getResources("META-INF/MANIFEST.MF").asScala.toVector

    val mainClasses = metaInfs.flatMap { url =>
      val attributes = new java.util.jar.Manifest(url.openStream()).getMainAttributes

      val vendor = Option(attributes.getValue("Specification-Vendor")).getOrElse("")
      val title = Option(attributes.getValue("Specification-Title")).getOrElse("")
      val mainClass = Option(attributes.getValue("Main-Class"))

      mainClass.map((vendor, title) -> _)
    }

    mainClasses.toMap
  }
}

object Util {

  def prematureExit(msg: String): Nothing = {
    Console.err.println(msg)
    sys.exit(255)
  }

  def prematureExitIf(cond: Boolean)(msg: => String): Unit =
    if (cond)
      prematureExit(msg)

  def exit(msg: String): Nothing = {
    Console.err.println(msg)
    sys.exit(1)
  }

  def exitIf(cond: Boolean)(msg: => String): Unit =
    if (cond)
      exit(msg)

}

class Helper(
  common: CommonOptions,
  rawDependencies: Seq[String],
  printResultStdout: Boolean = false,
  ignoreErrors: Boolean = false
) {
  import common._
  import Helper.errPrintln

  import Util._

  val cachePoliciesValidation = CacheParse.cachePolicies(common.mode)

  val cachePolicies = cachePoliciesValidation match {
    case Success(cp) => cp
    case Failure(errors) =>
      prematureExit(
        s"Error parsing modes:\n${errors.list.map("  "+_).mkString("\n")}"
      )
  }

  val cache = new File(cacheOptions.cache)

  val pool = Executors.newFixedThreadPool(parallel, Strategy.DefaultDaemonThreadFactory)

  val defaultRepositories = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  val repositoriesValidation = CacheParse.repositories(common.repository).map { repos0 =>

    var repos = (if (common.noDefault) Nil else defaultRepositories) ++ repos0

    if (common.sbtPluginHack)
      repos = repos.map {
        case m: MavenRepository => m.copy(sbtAttrStub = true)
        case other => other
      }

    if (common.dropInfoAttr)
      repos = repos.map {
        case m: IvyRepository => m.copy(dropInfoAttributes = true)
        case other => other
      }

    repos
  }

  val repositories = repositoriesValidation match {
    case Success(repos) => repos
    case Failure(errors) =>
      prematureExit(
        s"Error parsing repositories:\n${errors.list.map("  "+_).mkString("\n")}"
      )
  }


  val (modVerCfgErrors, moduleVersionConfigs) = Parse.moduleVersionConfigs(rawDependencies)

  prematureExitIf(modVerCfgErrors.nonEmpty) {
    s"Cannot parse dependencies:\n" + modVerCfgErrors.map("  "+_).mkString("\n")
  }


  val (forceVersionErrors, forceVersions0) = Parse.moduleVersions(forceVersion)

  prematureExitIf(forceVersionErrors.nonEmpty) {
    s"Cannot parse forced versions:\n" + forceVersionErrors.map("  "+_).mkString("\n")
  }

  val forceVersions = {
    val grouped = forceVersions0
      .groupBy { case (mod, _) => mod }
      .map { case (mod, l) => mod -> l.map { case (_, version) => version } }

    for ((mod, forcedVersions) <- grouped if forcedVersions.distinct.lengthCompare(1) > 0)
      errPrintln(s"Warning: version of $mod forced several times, using only the last one (${forcedVersions.last})")

    grouped.map { case (mod, versions) => mod -> versions.last }
  }

  val (excludeErrors, excludes0) = Parse.modules(exclude)

  prematureExitIf(excludeErrors.nonEmpty) {
    s"Cannot parse excluded modules:\n" +
    excludeErrors
      .map("  " + _)
      .mkString("\n")
  }

  val (excludesNoAttr, excludesWithAttr) = excludes0.partition(_.attributes.isEmpty)

  prematureExitIf(excludesWithAttr.nonEmpty) {
    s"Excluded modules with attributes not supported:\n" +
    excludesWithAttr
      .map("  " + _)
      .mkString("\n")
  }

  val excludes = excludesNoAttr.map { mod =>
    (mod.organization, mod.name)
  }.toSet

  val dependencies = moduleVersionConfigs.map {
    case (module, version, configOpt) =>
      Dependency(
        module,
        version,
        configuration = configOpt.getOrElse(defaultConfiguration),
        exclusions = excludes,
        transitive = !intransitive
      )
  }

  val checksums = {
    val splitChecksumArgs = checksum.flatMap(_.split(',')).filter(_.nonEmpty)
    if (splitChecksumArgs.isEmpty)
      Cache.defaultChecksums
    else
      splitChecksumArgs.map {
        case none if none.toLowerCase == "none" => None
        case sumType => Some(sumType)
      }
  }


  val startRes = Resolution(
    dependencies.toSet,
    forceVersions = forceVersions,
    filter = Some(dep => keepOptional || !dep.optional)
  )

  val logger =
    if (verbosityLevel >= 0)
      Some(new TermDisplay(new OutputStreamWriter(System.err)))
    else
      None

  val fetchs = cachePolicies.map(p =>
    Cache.fetch(cache, p, checksums = checksums, logger = logger, pool = pool)
  )
  val fetchQuiet = coursier.Fetch.from(
    repositories,
    fetchs.head,
    fetchs.tail: _*
  )
  val fetch0 =
    if (verbosityLevel >= 2) {
      modVers: Seq[(Module, String)] =>
        val print = Task {
          errPrintln(s"Getting ${modVers.length} project definition(s)")
        }

        print.flatMap(_ => fetchQuiet(modVers))
    } else
      fetchQuiet

  if (verbosityLevel >= 1) {
    errPrintln(s"  Dependencies:\n${Print.dependenciesUnknownConfigs(dependencies, Map.empty)}")

    if (forceVersions.nonEmpty) {
      errPrintln("  Force versions:")
      for ((mod, ver) <- forceVersions.toVector.sortBy { case (mod, _) => mod.toString })
        errPrintln(s"$mod:$ver")
    }
  }

  logger.foreach(_.init())

  val res = startRes
    .process
    .run(fetch0, maxIterations)
    .run

  logger.foreach(_.stop())

  val trDeps = res.minDependencies.toVector

  lazy val projCache = res.projectCache.mapValues { case (_, p) => p }

  if (printResultStdout || verbosityLevel >= 1) {
    if ((printResultStdout && verbosityLevel >= 1) || verbosityLevel >= 2)
      errPrintln(s"  Result:")
    val depsStr = Print.dependenciesUnknownConfigs(trDeps, projCache)
    if (printResultStdout)
      println(depsStr)
    else
      errPrintln(depsStr)
  }

  var anyError = false

  if (!res.isDone) {
    anyError = true
    errPrintln("\nMaximum number of iterations reached!")
  }

  if (res.errors.nonEmpty) {
    anyError = true
    errPrintln(
      s"\nError:\n" +
      res.errors.map {
        case (dep, errs) =>
          s"  ${dep.module}:${dep.version}:\n${errs.map("    " + _.replace("\n", "    \n")).mkString("\n")}"
      }.mkString("\n")
    )
  }

  if (res.conflicts.nonEmpty) {
    anyError = true
    errPrintln(
      s"\nConflict:\n" +
      Print.dependenciesUnknownConfigs(res.conflicts.toVector, projCache)
    )
  }

  if (anyError) {
    if (ignoreErrors)
      errPrintln("Ignoring errors")
    else
      sys.exit(1)
  }

  def artifacts(
    sources: Boolean,
    javadoc: Boolean,
    subset: Set[Dependency] = null
  ): Seq[Artifact] = {

    if (subset == null && verbosityLevel >= 1) {
      val msg = cachePolicies match {
        case Seq(CachePolicy.LocalOnly) =>
          "  Checking artifacts"
        case _ =>
          "  Fetching artifacts"
      }

      errPrintln(msg)
    }

    val res0 = Option(subset).fold(res)(res.subset)

    if (classifier0.nonEmpty || sources || javadoc) {
      var classifiers = classifier0
      if (sources)
        classifiers = classifiers :+ "sources"
      if (javadoc)
        classifiers = classifiers :+ "javadoc"

      res0.classifiersArtifacts(classifiers.distinct)
    } else
      res0.artifacts
  }

  def fetch(
    sources: Boolean,
    javadoc: Boolean,
    subset: Set[Dependency] = null
  ): Seq[File] = {

    val artifacts0 = artifacts(sources, javadoc, subset)

    val logger =
      if (verbosityLevel >= 0)
        Some(new TermDisplay(new OutputStreamWriter(System.err)))
      else
        None

    if (verbosityLevel >= 1 && artifacts0.nonEmpty)
      println(s"  Found ${artifacts0.length} artifacts")

    val tasks = artifacts0.map(artifact =>
      (Cache.file(artifact, cache, cachePolicies.head, checksums = checksums, logger = logger, pool = pool) /: cachePolicies.tail)(
        _ orElse Cache.file(artifact, cache, _, checksums = checksums, logger = logger, pool = pool)
      ).run.map(artifact.->)
    )

    logger.foreach(_.init())

    val task = Task.gatherUnordered(tasks)

    val results = task.run
    val errors = results.collect{case (artifact, -\/(err)) => artifact -> err }
    val files0 = results.collect{case (artifact, \/-(f)) => f }

    logger.foreach(_.stop())

    exitIf(errors.nonEmpty) {
      s"  Error:\n" +
      errors.map { case (artifact, error) =>
        s"${artifact.url}: $error"
      }.mkString("\n")
    }

    files0
  }
}
