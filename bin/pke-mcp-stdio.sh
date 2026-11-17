#!/usr/bin/env sh
# STDIO MCP launcher (M6): for MCP clients that start the server as a process. Runs as the key in PKE_API_KEY.
# Logs go to PKE_LOG_FILE (default <tmp>/pke-mcp-stdio.log); stdout carries MCP messages only.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
jar="${PKE_JAR:-$(ls "$here"/pke-engine/target/pke-engine-*.jar 2>/dev/null | grep -v original | head -n 1)}"
[ -f "$jar" ] || { echo "No engine jar; run: mvn -DskipTests package" >&2; exit 2; }
: "${PKE_API_KEY:?set PKE_API_KEY to a READER or CONTRIBUTOR key}"
exec java -jar "$jar" --spring.profiles.active=mcp-stdio "$@"
