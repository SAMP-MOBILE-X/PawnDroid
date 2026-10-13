#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
    echo "Missing gradle-wrapper.jar. Run scripts/setup-gradle.ps1 to install the verified Gradle wrapper jar." >&2
    exit 1
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
