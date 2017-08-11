name := "jsdisp"

version := "1.0.3-SNAPSHOT"

organization := "org.phasanix"

scalaVersion := "2.12.3"

libraryDependencies ++= Seq (
  "org.scala-lang"     %  "scala-compiler"  % scalaVersion.value,
  "com.typesafe.play"  %% "play-json"    % "2.6.2",
  "com.lihaoyi"        %% "utest"        % "0.4.8" % Test
)

resolvers ++= Seq (
  Resolver.defaultLocal
)

testFrameworks += new TestFramework("utest.runner.Framework")



