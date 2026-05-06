#!/bin/sh
# Gradle wrapper script
APP_HOME="$(dirname "$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")")"
exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
