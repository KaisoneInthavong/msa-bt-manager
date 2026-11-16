#!/bin/bash
set -e
# This is a simplified wrapper to call the jar directly if the shell script fails
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "Gradle wrapper jar not found!"
  exit 1
fi
exec java -Xmx512m -Dfile.encoding=UTF-8 -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
