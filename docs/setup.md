# Setup

## Requirements

- **JDK 17 or newer** to run the server. It must be a full JDK (the `jdk.jdi` module provides the debugger
  interface); a JRE runs everything except the `jvm_*` tools. The build uses JDK 21.
- **Microsoft Edge or Google Chrome** installed (rendering and the login window use the installed browser through
  Playwright; no browser download happens).
- **A local AEM** (AEM as a Cloud Service SDK quickstart or AEM 6.5) started with JDWP if you want JVM snapshots:

  ```
  java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8767 -jar aem-sdk-author-p4502.jar -gui
  ```

  On JDK 9+ `address=8767` listens on localhost only, which is what you want.

## Build

```
mvn -B verify                # compile, unit tests, JDI integration tests, stdio contract tests
mvn -B -DskipTests package   # just the server jar
```

The shaded jar is written to `target/aem-debug-mcp.jar` and copied to `plugin/server/aem-debug-mcp.jar`.
It bundles the Node.js runtime of the Playwright driver for the build platform only (Windows ~50 MB).
Build with `-Pall-platforms` for one jar that runs on Windows, macOS and Linux (~200 MB).

Optional test suites:

```
mvn -B verify -Pbrowser-tests   # also launches Edge/Chrome through Playwright
mvn -B verify -Pe2e             # also runs the E2E suite (and the browser tests) against the running local AEM
```

The E2E suite (`LocalAemE2ETest`) is read-only: no debug toggles, no deployments. It attaches to the JDWP port
only when the port is free (one non-suspending breakpoint on the login-page request, then it detaches).
Authenticated checks run when `AEM_USER`/`AEM_PASSWORD` are set, or with `-Daemdebug.e2e.login=true`, which
opens the AEM login window and waits for you to sign in. Set `AEM_E2E_PAGE=/content/<site>/<page>` to also run
`debug_page` on a real page. The findings are written to `target/e2e-findings.md`.

## Install as a GitHub Copilot plugin (Copilot CLI)

```
mvn -B -DskipTests package
copilot plugin install ./plugin
```

The plugin contains:

- `plugin.json` - plugin manifest
- `mcp.json` - starts `java -jar ${PLUGIN_ROOT}/server/aem-debug-mcp.jar` over stdio
- `skills/aem-local-debugging/SKILL.md` - teaches the agent the debugging loop and recipes
- `com.github.copilot/agents/aem-debugger.agent.md` - a diagnose-only agent: it can use every debug tool except
  `aem_debug_toggle`, plus reading and searching code, but cannot edit files, run commands or deploy. Copilot
  hands debugging tasks to it, or you select it with `/agent` (or `copilot --agent aem-debug-mcp:aem-debugger`;
  plugin agents carry the plugin name as a prefix). It returns a diagnosis with evidence and the file
  and line to fix, so screenshots, logs and snapshots stay out of your main conversation. Agents are
  Copilot-specific; the server and the skill also work in other clients.
- `bin/aem-debug-mcp.cmd|sh` - launchers with a Java version check (for standalone use)

Edit `plugin/mcp.json` (before installing) to change the AEM URLs, JDWP port or to add `AEM_PROJECT_DIR`.
`java` must be a JDK 17+ on the `PATH`; otherwise replace `"command": "java"` with the full path of your JDK's
`java` executable.

## Standalone (any MCP client)

### VS Code (`.vscode/mcp.json` in your AEM project)

```json
{
  "servers": {
    "aem-debug": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "C:/tools/aem-debug-mcp/aem-debug-mcp.jar"],
      "env": {
        "AEM_AUTHOR_URL": "http://localhost:4502",
        "AEM_JDWP_PORT": "8767",
        "AEM_PROJECT_DIR": "${workspaceFolder}"
      }
    }
  }
}
```

### IntelliJ IDEA (GitHub Copilot plugin, `mcp.json`)

```json
{
  "servers": {
    "aem-debug": {
      "type": "stdio",
      "command": "C:/Program Files/Java/jdk-21/bin/java.exe",
      "args": ["-jar", "C:/tools/aem-debug-mcp/aem-debug-mcp.jar"],
      "env": {
        "AEM_AUTHOR_URL": "http://localhost:4502",
        "AEM_JDWP_PORT": "8767",
        "AEM_PROJECT_DIR": "C:/code/mysite"
      }
    }
  }
}
```

IntelliJ and the MCP server cannot be attached to the AEM debug port at the same time (JDWP allows one
debugger). Stop the IntelliJ "Remote JVM Debug" session before the agent uses the `jvm_*` tools or
`debug_page`; the server detaches when asked (`jvm_connection action=detach`), on `debug_cleanup`, or after
`AEM_DEBUG_IDLE_DETACH_MINUTES` of inactivity, so you can reattach IntelliJ.

## Configuration

All settings are environment variables of the MCP server process and are optional.

| Variable | Default | Purpose |
|---|---|---|
| `AEM_AUTHOR_URL` | `http://localhost:4502` | Author instance (must be localhost/127.0.0.1/::1) |
| `AEM_PUBLISH_URL` | `http://localhost:4503` | Publish instance |
| `AEM_JDWP_HOST` / `AEM_JDWP_PORT` | `127.0.0.1` / `8767` | JDWP endpoint of AEM (local only) |
| `AEM_PROJECT_DIR` | - | A local AEM project folder for source mapping (see [Local project](#local-project)) |
| `AEM_LOG_DIR` | `<sling.home>/logs` | Where `error.log` is read from |
| `AEM_USER` / `AEM_PASSWORD` | - | Non-interactive login fallback (never logged or returned) |
| `AEM_DEBUG_BROWSER_CHANNEL` | auto (`msedge` on Windows, else `chrome`) | `msedge`, `chrome` or `chromium` |
| `AEM_DEBUG_ARTIFACTS_DIR` | `${PLUGIN_DATA}` or `%TEMP%/aem-debug-mcp` | Reports, screenshots, server log, toggle journal |
| `AEM_DEBUG_PROJECT_PACKAGES` | from the project index | Java packages watched for exceptions by default |
| `AEM_DEBUG_IDLE_DETACH_MINUTES` | `15` | Idle time before JDWP detach and debug-toggle revert (0 = never) |
| `AEM_DEBUG_LOGIN_WAIT_SECONDS` | `60` | How long a tool waits for the user to finish signing in |

## Local project

To link what AEM runs to your files, the server indexes the source of your local AEM project(s): Java (Sling
Models, servlets), HTL, component definitions, OSGi configs, client libraries and the Maven modules. It does not
decompile anything and does not read build logs. `jvm_classes` also reads line numbers from the compiled classes
in `target/classes`.

A chat can use several project folders at once, for example two repositories deployed into the same AEM. The
folders come from:

1. **The chat's folder.** Copilot does not tell MCP servers which folder a chat runs in, so the skill and the
   agent pass it at the start: `project_index workspace=<folder>`. It is used for that chat only.
2. **Folders named in a chat** with `project_index projectDir=<folder>`. They are remembered per AEM author URL in
   `projects.json`, so later chats use them without asking. `replace=true` keeps only the named folder;
   `remove=<folder>` forgets one.
3. `AEM_PROJECT_DIR`.
4. The client's workspace roots (in clients that share them, such as VS Code), and the server's working folder.

A folder counts when it is inside an AEM Maven project (`ui.apps`, `core`, `all`, `ui.config` or
`src/main/content` next to a `pom.xml`). A repository whose root `pom.xml` aggregates several AEM projects is
indexed as a whole, from any folder inside it. When no project is known, Copilot asks you where it is.

When you are logged in, `aem_status` and `project_index` check each project folder against the bundles installed
in AEM: `deployed`, `partly deployed`, or `NOT deployed here`. The last one means the folder is wrong or the
project is not deployed on this instance.

## Authentication

When a tool needs AEM data and there is no session, the server opens a **visible browser window on the AEM
login page**. Sign in there (local `admin` or SSO); the server captures the `login-token` cookie **in memory
only** and closes the window. Nothing is written to disk. `aem_auth action=logout` forgets the session.
For unattended use set `AEM_USER`/`AEM_PASSWORD` in the server environment (still never logged or returned).

## Files written

Everything goes to `AEM_DEBUG_ARTIFACTS_DIR`:

- `aem-debug-mcp.log` - server log (stdout is reserved for the MCP protocol)
- `reports/rpt-NNNN/` - report artifacts (also exposed as `aemdbg://reports/rpt-NNNN/<file>` resources)
- `toggle-journal.json` - debug toggles to revert (debug settings only, no credentials)
- `projects.json` - local project folders named in chats, per AEM author URL (paths only)
