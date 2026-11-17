# Claude Code and KGP setup (M6)

PKE serves MCP over streamable HTTP at `http://localhost:8089/mcp` and REST at `http://localhost:8089/api`. Both need
an API key (ADR-017). This page covers: creating keys, connecting Claude Code, installing the PKE skills, the STDIO
launcher, and the key KGP uses.

## 1. Start PKE and create keys

```bash
mvn -DskipTests package                       # builds pke-engine/target/pke-engine-0.1.0-SNAPSHOT.jar
java -jar pke-engine/target/pke-engine-0.1.0-SNAPSHOT.jar --spring.profiles.active=openai   # port 8089
```

Keys are created with the key CLI, which talks to the same Neo4j (`NEO4J_*` environment variables). The key is
printed **once** on stdout; only its SHA-256 is stored. Save it in your password manager or an environment variable,
never in a file in Git.

```bash
export PKE_API_KEY=$(bin/pke-keys.sh create william CONTRIBUTOR)   # Git Bash / Linux / macOS
bin/pke-keys.sh list                                                # names, roles, key prefixes, dates
bin/pke-keys.sh revoke william                                      # revokes every active key with that name
```

PowerShell, keeping the key in the user environment (new terminals and IDEs see it):

```powershell
$key = (bash bin/pke-keys.sh create william CONTRIBUTOR)
[Environment]::SetEnvironmentVariable('PKE_API_KEY', $key, 'User')
```

| Role | For |
|---|---|
| `READER` | Reading, search, task context, log explanations |
| `CONTRIBUTOR` | Plus ingestion and pipelines, answering questions, journey interviews, evolution requests (Claude Code, KGP) |
| `SCHEMA_ADMIN` | Plus schema registration, restore, data migrations (an operator, not an agent) |
| `EVOLUTION_OPERATOR` | Plus running evolution (M7) |

## 2. Connect Claude Code

Project-level `.mcp.json` (in the repository you work on; Claude Code expands `${PKE_API_KEY}` from the environment,
so the file holds no secret and can be committed):

```json
{
  "mcpServers": {
    "pke": {
      "type": "http",
      "url": "http://localhost:8089/mcp",
      "headers": { "Authorization": "Bearer ${PKE_API_KEY}" }
    }
  }
}
```

Or from the command line:

```bash
claude mcp add --transport http pke http://localhost:8089/mcp --header "Authorization: Bearer ${PKE_API_KEY}"
```

Check with `/mcp` in Claude Code: `pke` should list 20 tools (`docs/mcp-tools.md`). In Claude Code the tools are
named `mcp__pke__<tool>`, e.g. `mcp__pke__get_task_context`.

### STDIO instead of HTTP

For clients that start the server as a process (no running PKE needed, but each client starts its own JVM):

```json
{
  "mcpServers": {
    "pke": {
      "command": "sh",
      "args": ["C:/Code2025/Dukhan_Bank/BitBucket/project-knowledge-engine/bin/pke-mcp-stdio.sh"],
      "env": { "PKE_API_KEY": "${PKE_API_KEY}", "NEO4J_PASSWORD": "${NEO4J_PASSWORD}" }
    }
  }
}
```

The STDIO process authenticates once with `PKE_API_KEY` and refuses to start without a valid key; every tool call
runs as that key. Logs go to `PKE_LOG_FILE` (default `<tmp>/pke-mcp-stdio.log`) because stdout carries MCP messages.

## 3. Install the skills

The skills in `claude-skills/` are plain Claude Code skills. Copy them into the project you work on (or into
`~/.claude/skills/` for every project):

```bash
cp -r claude-skills/pke-* <your-project>/.claude/skills/
```

| Skill | What it does |
|---|---|
| `pke-bootstrap` | Registers repositories and BRDs (REST with `curl`), runs the ingestion pipelines, then goes through open questions |
| `pke-context` | Before a coding task: `get_task_context`, summarised with citations, plus affected components |
| `pke-questions` | Lists open questions and answers them with you (`answer_question`, kept as `HUMAN_VERIFIED`) |
| `pke-journey` | Captures a user journey by interview (`start_journey_interview`, `answer_journey_interview`) |

`pke-bootstrap` uses `curl` with `$PKE_API_KEY` for registration (there is no MCP tool for it); the others use MCP
only. Answering, interviews and ingestion need a CONTRIBUTOR key.

## 4. KGP (knowledge-graph-platform)

KGP calls PKE over REST on port **8089** with its own CONTRIBUTOR key, named `kgp`, so its changes are recorded as
`key:kgp` (plus the `X-PKE-Actor` header KGP already sends, e.g. `key:kgp/kgp`).

1. Create the key once (PKE's Neo4j must be running):

   ```powershell
   $key = (bash bin/pke-keys.sh create kgp CONTRIBUTOR)
   [Environment]::SetEnvironmentVariable('PKE_API_KEY', $key, 'User')
   ```

   KGP reads the key from the `PKE_API_KEY` environment variable. Never put it in `kgp.yml`.
2. In KGP's `kgp.yml`, point `pke.baseUrl` at `http://localhost:8089`.
3. KGP must send `Authorization: Bearer <PKE_API_KEY>` on every request.

**State of KGP on 2026-11-17 (not changed by PKE M6; this repository does not edit KGP):** KGP's defaults still say
port 8085 (its decision D-53) and its `PkeClient` sends only `X-PKE-Actor`, no key. Until KGP adds the header and the
new base URL, its calls get `401`. The KGP-side change is two lines in `PkeClient` (read `System.getenv("PKE_API_KEY")`,
add the header) and the `baseUrl` default.

If the key leaks: `bin/pke-keys.sh revoke kgp`, create a new one, update the environment variable.

## 5. Execution policy

What MCP callers may start depends on the deployment profile (`PKE_POLICY_PROFILE`: `dev` default, `test`, `prod`;
`definitions/policies/execution.yaml`): which pipelines `trigger_ingestion` may start, and which kinds of evolution
request `submit_evolution_request` accepts. In `prod`, `reindex` and `discover-relationships` are started by an
operator over REST. Roles are checked first, then the policy; refused calls are audited.
