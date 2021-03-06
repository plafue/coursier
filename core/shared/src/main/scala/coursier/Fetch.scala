package coursier

import coursier.maven.MavenSource

import scalaz._

object Fetch {

  type Content[F[_]] = Artifact => EitherT[F, String, String]


  type MD = Seq[(
    (Module, String),
    Seq[String] \/ (Artifact.Source, Project)
  )]

  type Metadata[F[_]] = Seq[(Module, String)] => F[MD]

  /**
    * Try to find `module` among `repositories`.
    *
    * Look at `repositories` from the left, one-by-one, and stop at first success.
    * Else, return all errors, in the same order.
    *
    * The `version` field of the returned `Project` in case of success may not be
    * equal to the provided one, in case the latter is not a specific
    * version (e.g. version interval). Which version get chosen depends on
    * the repository implementation.
    */
  def find[F[_]](
    repositories: Seq[Repository],
    module: Module,
    version: String,
    fetch: Content[F]
  )(implicit
    F: Monad[F]
  ): EitherT[F, Seq[String], (Artifact.Source, Project)] = {

    val lookups = repositories
      .map(repo => repo -> repo.find(module, version, fetch).run)

    val task = lookups.foldLeft[F[Seq[String] \/ (Artifact.Source, Project)]](F.point(-\/(Nil))) {
      case (acc, (repo, eitherProjTask)) =>
        val looseModuleValidation = repo match {
          case m: MavenRepository => m.sbtAttrStub // that sucks so much
          case _ => false
        }
        val moduleCmp = if (looseModuleValidation) module.copy(attributes = Map.empty) else module
        F.bind(acc) {
          case -\/(errors) =>
            F.map(eitherProjTask)(_.flatMap{case (source, project) =>
              val projModule =
                if (looseModuleValidation)
                  project.module.copy(attributes = Map.empty)
                else
                  project.module
              if (projModule == moduleCmp) \/-((source, project))
              else -\/(s"Wrong module returned (expected: $moduleCmp, got: ${project.module})")
            }.leftMap(error => error +: errors))

          case res @ \/-(_) =>
            F.point(res)
        }
    }

    EitherT(F.map(task)(_.leftMap(_.reverse)))
      .map {case x @ (source, proj) =>
        val looseModuleValidation = source match {
          case m: MavenSource => m.sbtAttrStub // omfg
          case _ => false
        }
        val projModule =
          if (looseModuleValidation)
            proj.module.copy(attributes = Map.empty)
          else
            proj.module
        val moduleCmp = if (looseModuleValidation) module.copy(attributes = Map.empty) else module
        assert(projModule == moduleCmp)
        x
      }
  }

  def from[F[_]](
    repositories: Seq[core.Repository],
    fetch: Content[F],
    extra: Content[F]*
  )(implicit
    F: Nondeterminism[F]
  ): Metadata[F] = {

    modVers =>
      F.map(
        F.gatherUnordered(
          modVers.map { case (module, version) =>
            def get(fetch: Content[F]) =
              find(repositories, module, version, fetch)
            F.map((get(fetch) /: extra)(_ orElse get(_))
              .run)((module, version) -> _)
          }
        )
      )(_.toSeq)
  }

}