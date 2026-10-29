#!/usr/bin/env sh
# Minimal Gradle wrapper script.
# This project vendors gradle-wrapper.jar so it can bootstrap without any preinstalled Gradle.

APP_HOME=$(cd "$(dirname "$0")" && pwd)

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$CLASSPATH" ]; then
  echo "Missing gradle-wrapper.jar at $CLASSPATH" >&2
  exit 1
fi

exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
