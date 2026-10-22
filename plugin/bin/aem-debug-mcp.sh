#!/usr/bin/env sh
# Starts the aem-debug-mcp server (MCP over stdio). Nothing but the server may write to stdout.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/../server/aem-debug-mcp.jar"

if [ -n "$AEM_DEBUG_JAVA" ]; then
  JAVA_EXE="$AEM_DEBUG_JAVA"
elif [ -n "$JAVA_HOME" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
else
  JAVA_EXE=java
fi

if [ ! -f "$JAR" ]; then
  echo "aem-debug-mcp: $JAR not found. Build it with: mvn -DskipTests package" >&2
  exit 1
fi

if ! "$JAVA_EXE" -jar "$JAR" --version >/dev/null 2>&1; then
  echo "aem-debug-mcp: '$JAVA_EXE' cannot run the server. A JDK 17 or newer is required (a full JDK, so that jdk.jdi is available). Set JAVA_HOME or AEM_DEBUG_JAVA." >&2
  exit 1
fi

# shellcheck disable=SC2086
exec "$JAVA_EXE" $AEM_DEBUG_JAVA_OPTS -jar "$JAR" "$@"
