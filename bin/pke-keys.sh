#!/usr/bin/env sh
# API keys (M6, ADR-017). Runs the engine jar as a one-shot CLI against the configured Neo4j (NEO4J_URI,
# NEO4J_USERNAME, NEO4J_PASSWORD, NEO4J_DATABASE), without starting the web server.
#   bin/pke-keys.sh create <name> <READER|CONTRIBUTOR|SCHEMA_ADMIN|EVOLUTION_OPERATOR>[,...]
#       prints the key once on stdout (only its SHA-256 is stored), e.g.  export PKE_API_KEY=$(bin/pke-keys.sh create kgp CONTRIBUTOR)
#   bin/pke-keys.sh list
#   bin/pke-keys.sh revoke <name>
# PKE_JAR overrides the jar (default pke-engine/target/pke-engine-*.jar; build it with mvn -DskipTests package).
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
jar="${PKE_JAR:-$(ls "$here"/pke-engine/target/pke-engine-*.jar 2>/dev/null | grep -v original | head -n 1)}"
[ -f "$jar" ] || { echo "No engine jar; run: mvn -DskipTests package" >&2; exit 2; }
exec java -jar "$jar" --pke.cli=keys --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=WARN --pke.index.worker-enabled=false "$@"
