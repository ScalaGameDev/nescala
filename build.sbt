name := "nescala"

version := "0.2"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq("org.scala-lang.modules" %% "scala-swing" % "2.0.0",
                            "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
                            "com.typesafe" % "config" % "1.3.0",
                            "org.lwjgl.lwjgl" % "lwjgl" % "2.9.3",
                            "net.java.jinput" % "jinput-platform" % "2.0.7" pomOnly()
)

lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin).
  settings(
    autoCompilerPlugins := true,
    mainClass in Compile := Some("com.owlandrews.nescala.ui.Run"),
    fork in run := true,
    scalacOptions ++= Seq("-feature", "-deprecation"),
    javaOptions in run ++= Seq("-XX:UseSSE=3", "-XX:+UseConcMarkSweepGC", "-Xms256m"),
    javaOptions += "-Djava.library.path=libs/",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.owlandrews.nescala"
  )