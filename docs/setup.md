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

  Publish is a separate JVM. To capture exceptions and snapshots on publish too, start it with its own port, for
  example `address=8768` (the plugin's `mcp.json` sets `AEM_PUBLISH_JDWP_PORT=8768`). Rendering, logs, content
  reads and the Recent Requests console work on publish without JDWP.

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
| `AEM_JDWP_HOST` / `AEM_JDWP_PORT` | `127.0.0.1` / `8767` | JDWP endpoint of author (local only) |
| `AEM_PUBLISH_JDWP_PORT` | none (plugin: `8768`) | JDWP port of publish; without it there is no JVM capture on publish |
| `AEM_PROJECT_DIR` | - | A local AEM project folder for source mapping (see [Local project](#local-project)) |
| `AEM_LOG_DIR` | `<sling.home>/logs` | Where author's `error.log` is read from |
| `AEM_PUBLISH_LOG_DIR` | `<sling.home>/logs` | Where publish's `error.log` is read from |
| `AEM_DEV_SERVERS` | - | Front-end dev servers and the instance they proxy to, e.g. `http://localhost:3000=publish` (comma-separated; see [Publish and front-end dev servers](#publish-and-front-end-dev-servers)) |
| `AEM_USER` / `AEM_PASSWORD` | - | Non-interactive login fallback (never logged or returned) |
| `AEM_DEBUG_REMEMBER_LOGINS` | `true` | Remember AEM login tokens for later chats in an owner-only file; `false` = memory only |
| `AEM_DEBUG_BROWSER_CHANNEL` | auto (`msedge` on Windows, else `chrome`) | `msedge`, `chrome` or `chromium` |
| `AEM_DEBUG_ARTIFACTS_DIR` | `${PLUGIN_DATA}` or `%TEMP%/aem-debug-mcp` | Reports, screenshots, server log, toggle journal, remembered settings |
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

## Publish and front-end dev servers

**Publish pages**: pass `instance=publish` with a path, or a publish URL (`http://localhost:4503/...`); a URL of
an instance always selects that instance. Renders of publish show the page as a visitor sees it: anonymous, or
with the captured site session (below). `session=aem` renders with the AEM login of publish instead.

**Front-end dev servers** (for example a React, webpack or Vite dev server on `localhost:3000` that proxies some
paths to AEM): pass the dev-server URL as `path`. The page is rendered exactly as given; a URL whose origin is
not an AEM instance is never rewritten to one. The first time, also pass `instance=publish` or
`instance=author`: the AEM instance that dev server proxies to. To find it, check the dev server's proxy
configuration (`setupProxy.js`, `devServer.proxy`, `server.proxy` or the `"proxy"` field of `package.json`).
The answer is remembered in `dev-servers.json` for later calls and chats; `AEM_DEV_SERVERS` configures it up
front.

What `debug_page` gives you on a dev-server page:

- the browser side as usual: screenshot, HTTP statuses, failed requests, console errors, network log;
- the server side of the instance behind it (its error.log, Recent Requests and, with JDWP, JVM), matched to the
  browser requests by method and path. This works when the proxy forwards paths unchanged; a "Dev server"
  section lists the requests that reached AEM.

Many dev servers answer every page URL with their own app shell (single-page apps), so the document itself is
not rendered by AEM: there is no component map and no Request Progress Tracker for it. `components=true` maps
components when the dev server proxies the page itself to AEM.

**JVM capture on publish** needs publish started with JDWP on its own port (see [Requirements](#requirements)).
Without `AEM_PUBLISH_JDWP_PORT`, `jvm_connection action=attach instance=publish port=<port>` sets the port for
the current chat. All `jvm_*` tools take `instance=publish`.

## Authentication

**AEM logins (author and publish).** When a tool needs AEM data and there is no session, the server opens a
**visible browser window on the AEM login page** of that instance. Sign in there (local `admin` or SSO); the
server captures the `login-token` cookie and closes the window. Calls that need the same login while the window
is open wait for that one window; it is never replaced by a new one.

The token is remembered in `aem-logins.json` so that a new chat (every chat starts its own server) does not ask
again. The file holds only login tokens, never passwords; it is readable only by your OS user, entries expire
after 7 days, and an entry is removed when AEM rejects the token or on `aem_auth action=logout`. Set
`AEM_DEBUG_REMEMBER_LOGINS=false` to keep logins in memory only. For unattended use set
`AEM_USER`/`AEM_PASSWORD` in the server environment (still never logged or returned).

Browsers do not scope cookies by port: author and publish both set `login-token` on `localhost`, and cookies of
`localhost:4503` are also sent to a dev server on `localhost:3000` (which may forward them). The server keeps
each instance's token separately and gives every render exactly one identity.

**Site visitor login.** For pages that need a site's own login (a customer account, often through an external
identity provider), `aem_auth action=site-login url=<page>` opens a visible window on that local page. Sign in
there with any login flow and close the window when the site shows you signed in. The server captures the
cookies of local hosts and the local and session storage of local origins; the identity provider's own cookies
are dropped. This site session is kept **in memory only** and never written to disk. Renders of publish and
dev-server pages use it automatically (`session=auto`); `session=none` renders anonymously, and
`aem_auth action=site-logout` forgets it.

## Files written

Everything goes to `AEM_DEBUG_ARTIFACTS_DIR`:

- `aem-debug-mcp.log` - server log (stdout is reserved for the MCP protocol)
- `reports/rpt-NNNN/` - report artifacts (also exposed as `aemdbg://reports/rpt-NNNN/<file>` resources)
- `toggle-journal.json` - debug toggles to revert (debug settings only, no credentials)
- `projects.json` - local project folders named in chats, per AEM author URL (paths only)
- `aem-logins.json` (+ `aem-logins.json.lock`) - remembered AEM login tokens per instance, owner-only (not with
  `AEM_DEBUG_REMEMBER_LOGINS=false`)
- `dev-servers.json` - front-end dev servers and the AEM instance each one proxies to
