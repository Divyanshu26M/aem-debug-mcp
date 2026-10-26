# aem-debug-mcp

An MCP server, shipped as a GitHub Copilot plugin, that lets an AI agent debug **local** AEM development end to
end. The agent renders pages on your local AEM SDK, maps what it sees to components and source files, reads
content and OSGi configuration, and captures JVM state over JDWP. Everything is matched to the HTTP request
that produced the page, so you no longer paste screenshots, logs or variable values into the chat.

```
browser render ──► component map ──► Sling Models / HTL / local source
      │                                        ▲
      └── request thread "<ip> [<start>] GET /content/... HTTP/1.1" ──┘
              ├─ exceptions at the throw site (JDI, caught or not)
              ├─ snapshot breakpoints / logpoints (never suspend AEM)
              ├─ error.log lines of that thread
              └─ Request Progress Tracker of that request
```

## What it does

- **Render and see**: headless Edge/Chrome renders the page as a visitor sees it. You get a screenshot, the HTTP
  status, failed requests (for example a clientlib 404) and console errors.
- **Map pixels to code**: every component on the page gets a number (#n) on the screenshot. For each one you
  get its resource path, resource type, whether it rendered empty, the Sling Model behind it and the local
  model/HTL files. The local source comes from the chat's folder or from project folders you name once
  (several repositories, or one repository that aggregates several AEM projects); see
  [docs/setup.md](docs/setup.md#local-project).
- **Read content and config**: JCR resources, model JSON, QueryBuilder, page context (template, policies,
  context-aware config), effective OSGi configurations, bundle/component/Sling Model health, servlet
  resolution, error.log, and the Recent Requests console. All of this is read-only.
- **Look inside the JVM, without pausing it**: snapshot breakpoints and logpoints, plus exceptions captured at
  the throw site (including swallowed ones). These come over the JDWP port that AEM already opens for IntelliJ.
  Captured threads resume immediately.
- **Correlate**: `debug_page` does all of the above in one call and groups the server-side events per browser
  request. The result is a Debug Report with hints, plus artifacts (HTML, screenshots, logs, RPT, snapshots).
- **Is my change deployed?** `jvm_classes` compares the class running in AEM (bundle, revision, line table)
  with your local build.

## Tools (24)

| Area | Tools |
|---|---|
| Setup and login | `aem_status`, `aem_auth` |
| Content and config (read-only) | `aem_get_resource`, `aem_query`, `aem_get_model_json`, `aem_page_context`, `aem_get_osgi_config`, `aem_osgi_status`, `aem_resolve` |
| Logs and requests | `aem_logs`, `aem_recent_requests`, `aem_debug_toggle` |
| Rendering and components | `page_render`, `page_component_map`, `component_inspect`, `project_index` |
| JVM over JDWP | `jvm_connection`, `jvm_breakpoint`, `jvm_exception_watch`, `jvm_snapshots`, `jvm_classes` |
| One-call workflows | `debug_page`, `debug_capture`, `debug_cleanup` |

See [docs/tools.md](docs/tools.md) for what each tool does. The bundled
[`aem-local-debugging` skill](plugin/skills/aem-local-debugging/SKILL.md) teaches the agent the debugging loop.
In Copilot, the plugin also adds the diagnose-only
[`aem-debugger` agent](plugin/com.github.copilot/agents/aem-debugger.agent.md). Copilot can hand it a debugging
task; it uses the debug tools, cannot change anything, and returns the cause with the file and line to fix.

## Safety model

- **Local only**: only `localhost`, `127.0.0.1` and `::1` are reachable (author and publish). Remote hosts are
  refused.
- **Read-only**, with one exception: `aem_debug_toggle` can switch debug-only OSGi settings (WCM debug filter,
  Recent Requests history, RPT logging, log levels). It does so only after you confirm the exact change. Each
  change is journaled and reverted by `debug_cleanup`, after inactivity, when the server stops, or on the next
  start after a crash.
- **Credentials never pass through the chat**: when AEM needs a login, a visible browser window opens on the
  AEM login page (local admin or SSO). The session cookie is kept in memory only. The `AEM_USER` and
  `AEM_PASSWORD` environment variables are an optional non-interactive fallback. Passwords are never logged,
  written to disk or returned in tool output, and tool output masks secrets.
- **JDWP is exclusive**: AEM accepts one debugger at a time. The server attaches on demand and detaches on
  `jvm_connection action=detach`, on `debug_cleanup`, or after inactivity, so IntelliJ can reconnect. It
  never suspends the whole VM and never calls `VirtualMachine.exit`.

## Requirements

- JDK 17 or later to run the server (JDK 21 recommended). A full JDK is needed for JVM debugging because the
  server uses the `jdk.jdi` module.
- Microsoft Edge or Google Chrome installed (used headless; no browser download is needed).
- A local AEM SDK (or 6.5) started with JDWP enabled, for example:

  ```
  java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8767 -jar aem-sdk-author-p4502.jar -gui
  ```

## Install

**Copilot plugin** (server, skill and agent):

```
mvn -B -DskipTests package          # builds target/aem-debug-mcp.jar and copies it to plugin/server/
copilot plugin install ./plugin
```

**Standalone MCP server** (VS Code, IntelliJ or any MCP client): run `java -jar aem-debug-mcp.jar` over stdio.
[docs/setup.md](docs/setup.md) has ready-to-paste `mcp.json` snippets and all configuration variables (AEM
URLs, JDWP host/port, project directory, log directory, browser channel, idle timeout).

## Typical session

1. "The hero on /content/wknd/us/en is empty" → the agent calls `aem_status`, then
   `debug_page path=/content/wknd/us/en`.
2. The report shows component #4 `hero` rendered empty, model `HeroModel`, a caught
   `MissingElementsException` at `HeroModel.java:41` on that request, and the hint "JCR has `jcr:title` but the
   field injects `title`".
3. The agent fixes the model, you redeploy, `jvm_classes class=com.mysite.core.models.HeroModel` confirms the
   new build is running, and a second `debug_page` verifies the fix.
4. `debug_cleanup` detaches JDWP and reverts any debug toggles.

## Build and test

```
mvn -B verify                 # unit tests + JDI tests against a local fixture JVM + stdio IT of the shaded jar
mvn -B verify -Pe2e           # opt-in end-to-end checks against your running local AEM
mvn -B verify -Pbrowser-tests # opt-in tests that launch a headless browser
mvn -B package -Pall-platforms  # universal jar with Playwright drivers for every OS (default: current OS only)
```

The default build needs no network access and no running AEM.

## Project layout

```
src/main/java/dev/aemdebug/
  server/     MCP wiring (stdio), composition root, cleanup coordinator, report resources
  tools/      tool framework; tools/impl = the 24 tools
  aem/        local-only HTTP client, auth (login window / env fallback), Felix console, content reads
  browser/    Playwright-based rendering, screenshots, login window
  mapping/    DOM <-> component <-> model <-> source mapping
  jdi/        snapshot-first JDI engine (breakpoints, logpoints, exception watches, class versions)
  logs/ rpt/  error.log reader/parser, Request Progress Tracker parser, Recent Requests client
  correlate/  request keys (Sling thread names) and browser/server correlation
  toggles/    confirmed, journaled, auto-reverted debug OSGi toggles
  project/    local project indexer (models, components, HTL, servlets, OSGi configs, clientlibs)
fixture-app/  tiny JVM app used by the JDI tests
plugin/       Copilot plugin: plugin.json, mcp.json, skills/aem-local-debugging,
              com.github.copilot/agents/aem-debugger, launchers
docs/         setup, tools, troubleshooting, spike findings
```

## Status and limits

This is an MVP. Interactive suspend/step debugging and a companion OSGi bundle are planned for later phases.
Some AEM console formats are implemented from documentation and parser fixtures and are still to be verified
against a logged-in AEM. See [docs/spike-findings.md](docs/spike-findings.md) and
[docs/troubleshooting.md](docs/troubleshooting.md).
