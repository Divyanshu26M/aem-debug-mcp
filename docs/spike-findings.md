# AEM signal spike: what is verified and what is assumed

The MVP relies only on what AEM provides out of the box (no companion bundle). This page records which of those
signals were verified against a running local AEM SDK, and which are implemented from documentation and Sling
sources with fallbacks and parser fixtures, still waiting for a logged-in end-to-end run (`mvn verify -Pe2e`, see
`LocalAemE2ETest`).

## Verified

| Signal | How it was verified |
|---|---|
| Login page `/libs/granite/core/content/login.html` answers 200 anonymously | `aem_status` against the local author (SDK on 4502) |
| JDWP agent on `127.0.0.1:8767` completes the JDWP handshake when no debugger is attached | `aem_status` / `JdwpPortProbe` against the local author |
| Sling request thread name `<remoteAddr> [<System.currentTimeMillis()>] <METHOD> <requestURI> <protocol>` (URI without query) | `SlingMainServlet.setThreadName` source, and on the SDK: a snapshot in `SlingRequestProcessorImpl.doProcessRequest` parsed to `GET /libs/granite/core/content/login.html` (`LocalAemE2ETest`) |
| Attach / method breakpoint / snapshot / detach on the real SDK (HotSpot 21.0.12.1): the breakpoint armed in `org.apache.sling.engine` 2.16.6, locals and `this` were captured, the request completed (HTTP 200) and the port returned to listening after detach | `LocalAemE2ETest` (`mvn verify -Pe2e`) |
| Felix `BundleClassLoader` internals (`m_wiring.m_revision`) name the bundle of a loaded class: `bundle 595 org.apache.sling.engine 2.16.6 (revision 595.0)` | `LocalAemE2ETest` (`classVersions` of `SlingMainServlet`) |
| Non-suspending capture, exceptions at the throw site (caught and uncaught), deferred arming, logpoints, conditions, flood protection, several breakpoints on one line, class line tables | `JdiEngineFixtureTest` against a real JVM (fixture app with Sling-style thread names) |
| MCP contract (24 tools with descriptions and object schemas, report resources, clean stdout, exit on stdin EOF) | `StdioServerIT` against the shaded jar, plus an independent TypeScript MCP client smoke test |
| Copilot CLI 1.0.103 as MCP client: no workspace roots, server started in the plugin folder (not the chat's folder), elicitation supported. So the chat's folder must be passed explicitly (`project_index workspace=`) | Server log of a plugin session started in an AEM repository |
| Project detection on a real repository: an aggregator `pom.xml` with two archetype AEM projects is detected from its root and indexed as one project in 1.4 s (24 modules, 533 classes, 159 Sling Models, 299 components) | `project_index workspace=` from Copilot CLI |

## Implemented, to be verified with a logged-in session

| Signal | Used by | Fallback if it differs |
|---|---|---|
| `/libs/granite/security/currentuser.json` | login state | `/system/sling/info.sessionInfo.json` |
| `/system/console/bundles.json`, `/bundles/<id>.json`, `/components.json` | `aem_status`, `aem_osgi_status` | error is reported per section |
| `/system/console/status-<printer>.txt` (`slingsettings`, `Configurations`, `slingmodels`, `osgi-installer`, `productinfo`) | run modes, sling.home, Sling Models, versions | sections report "unavailable" |
| `/libs/granite/operations/content/systemoverview/export.json` | AEM version | `productinfo` printer |
| `/system/console/configMgr/<pid>.json` and the LDAP filter form `(service.factoryPid=...)` | `aem_get_osgi_config`, toggles | `Configurations` printer text |
| configMgr POST (`apply`, `action=ajaxConfigManager`, `propertylist`, `factoryPid`, `delete`) with `/libs/granite/csrf/token.json` | `aem_debug_toggle` (only after user confirmation) | the toggle verifies by reading back and reports a mismatch |
| `/system/console/requests` HTML (list and `?index=<key>` with the RPT inside `<pre>`) | `aem_recent_requests`, `debug_page` | parser fixtures in `RptParserTest`; "no matching entry" note |
| `/system/console/slinglog/tailer.txt?tail=&grep=&name=/logs/error.log` | `aem_logs` when error.log is not readable locally | local file via sling.home or `AEM_LOG_DIR` |
| `/system/console/servletresolver?url=&method=` | `aem_resolve` | RPT of the request |
| Edit-mode markup `<cq data-path=... data-config=...>` (`?wcmmode=edit`) and WCM debug comments `<!--cq{...}-->` | `page_component_map` | Core Components ids, class order |
| Core Components id = last segment of the resource type + `-` + first 10 hex chars of sha256(path) (or the `id` property) | `page_component_map` | edit-mode markup |
| Interactive login: `login-token` cookie captured from the visible browser window (local admin and SSO) | `aem_auth` | `AEM_USER`/`AEM_PASSWORD` with `j_security_check` |
| Debug toggle PIDs: `com.day.cq.wcm.core.impl.WCMDebugFilter` (`wcmdbgfilter.enabled`), `org.apache.sling.engine.impl.SlingMainServlet` / `...debug.RequestInfoProviderImpl` (`sling.max.record.requests`, `sling.store.pattern.requests`), `org.apache.sling.commons.log.LogManager.factory.config`, logger `org.apache.sling.engine.impl.debug.RequestProgressTrackerLogFilter` | `aem_debug_toggle` | plan shows the current values first |

## How to complete the spike

1. Start the local author with JDWP and detach IntelliJ.
2. `mvn -B verify -Pe2e -Daemdebug.e2e.login=true` (a login window opens; sign in), optionally with
   `AEM_E2E_PAGE=/content/<site>/<page>`.
3. Read `target/e2e-findings.md` and update this page.
