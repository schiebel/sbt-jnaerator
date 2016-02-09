package com.timcharper.sbt

import java.io.File
import sbt.{config => sbtConfig, _}
import sbt.Keys.{cleanFiles, libraryDependencies, managedSourceDirectories,
  sourceDirectories, sourceDirectory, sourceGenerators, sourceManaged, streams,
  version, watchSources}

object JnaeratorPlugin extends AutoPlugin {
  object autoImport {

    val jnaerator = sbtConfig("jnaerator")

    val jnaeratorTargets = SettingKey[Seq[Jnaerator.Target]]("jnaerator-targets",
      "List of header-files and corresponding configuration for java interface generation")
    val jnaeratorGenerate = TaskKey[Seq[File]]("jnaerator-generate",
      "Run jnaerate and generate interfaces")
    val jnaeratorRuntime = SettingKey[Jnaerator.Runtime]("which runtime to use")

    object Jnaerator {
      sealed trait Runtime
      object Runtime {
        case object JNA extends Runtime
        case object BridJ extends Runtime
      }
      case class Target(
        headerFile: File,
        packageName: String,
        libraryName: String,
        extraArgs: Seq[String] = Nil)

      lazy val settings = inConfig(jnaerator)(Seq[Setting[_]](
        sourceDirectory := ((sourceDirectory in Compile) { _ / "native" }).value,
        sourceDirectories := ((sourceDirectory in Compile) { _ :: Nil }).value,
        sourceManaged := ((sourceManaged in Compile) { _ / "jnaerator_interfaces" }).value,
        jnaeratorGenerate <<= runJnaerator
      )) ++ Seq[Setting[_]](
        jnaeratorTargets := Nil,
        jnaeratorRuntime := Runtime.BridJ,
        version := ((jnaeratorRuntime in jnaerator) {
          /* Latest versions against which the targetted version of JNAerator is
           * known to be compatible */
          case Runtime.JNA => "4.2.1"
          case Runtime.BridJ => "0.7.0"
        }).value,
        cleanFiles += (sourceManaged in jnaerator).value,
        watchSources ++= ((jnaeratorTargets in jnaerator) { _.map(_.headerFile) }).value,
        sourceGenerators in Compile += (jnaeratorGenerate in jnaerator).taskValue,
        managedSourceDirectories in Compile += (sourceManaged in jnaerator).value,
        libraryDependencies += (jnaeratorRuntime in jnaerator, version in jnaerator).apply {
          case (Jnaerator.Runtime.JNA, v) =>
            "net.java.dev.jna" % "jna" % v
          case (Jnaerator.Runtime.BridJ, v) =>
            "com.nativelibs4java" % "bridj" % v
        }.value
      )

    }

    private def runJnaerator: Def.Initialize[Task[Seq[File]]] =
      (streams, jnaeratorTargets in jnaerator, jnaeratorRuntime in jnaerator, sourceManaged in jnaerator) map {
        (streams, jnaeratorTargets, runtime, sourceManaged) =>

        jnaeratorTargets.flatMap { target =>
          val targetId = s"${target.headerFile.getName}-${(target, jnaeratorRuntime, sourceManaged).hashCode}"
          val cachedCompile = FileFunction.cached(streams.cacheDirectory / "jnaerator", inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) { (_: Set[File]) =>
            IO.delete(sourceManaged)
            sourceManaged.mkdirs()

	          // java -jar bin/jnaerator.jar -package com.spacemonkey.doubler -library doubler lib/libdoubler.h -o src/main/java -mode Directory -f -scalaStructSetters
            val args = List(
              "-package", target.packageName,
              "-library", target.libraryName,
              target.headerFile.getCanonicalPath,
              "-o", sourceManaged.getCanonicalPath,
              "-mode", "Directory",
              "-f", "-scalaStructSetters") ++ target.extraArgs

            streams.log.info(s"(${target.headerFile.getName}) Running JNAerator with args ${args.mkString(" ")}")
            try {
              com.ochafik.lang.jnaerator.JNAerator.main(args.toArray)
            } catch { case e: Exception =>
                throw new RuntimeException(s"error occured while running jnaerator: ${e.getMessage}", e)
            }

            (sourceManaged ** "*.java").get.toSet
          }
          cachedCompile(Set(target.headerFile)).toSeq
        }
      }
  }
}
