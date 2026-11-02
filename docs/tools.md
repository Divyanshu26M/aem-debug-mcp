# Tools

All tools talk to **local** AEM instances only. Each tool publishes its full argument schema over MCP
(`tools/list`); this page explains what the tools are for and how they fit together. Results that are too large
for the chat are saved as report artifacts: `aemdbg://reports/<id>/<name>` resources, also readable as local
files under the artifacts directory.

## Setup and login

| Tool | What it does |
|---|---|
| `aem_status` | Setup check: author/publish reachability, login state, AEM version, run modes, sling.home and error.log location, bundle health, known front-end dev servers, the site session, and whether each instance's JDWP port is free or held by another debugger. Call it first. |
| `aem_auth` | `status`, `login` (opens a visible browser window on the AEM login page of author or publish; local admin or SSO; the login token is remembered for later chats in an owner-only file) and `logout`. `site-login` opens a visible window on a local site page for the site's own visitor login (external identity providers work); the captured cookies and storage of local origins stay in memory and are used by renders of publish and dev-server pages. `site-logout` forgets them. |

## Content and configuration (read-only)

| Tool | What it does |
|---|---|
| `aem_get_resource` | A JCR resource as JSON with a chosen depth (falls back to smaller depths when AEM refuses large trees). |
| `aem_query` | QueryBuilder search (`/bin/querybuilder.json`). |
| `aem_get_model_json` | The Sling Model exporter output (`<path>.model.json`) of a page or component. |
| `aem_page_context` | Template, content policy of a component, context-aware configuration and language of a page. |
| `aem_get_osgi_config` | Effective OSGi configuration of a PID (including factory configurations), plus the matching config files in the local project. |
| `aem_osgi_status` | Bundles, Declarative Services components and registered Sling Models that are not healthy, filterable by name or package. |
| `aem_resolve` | Which script or servlet handles a URL (Sling servlet resolver). A URL of an instance selects that instance. |

## Logs and requests

| Tool | What it does |
|---|---|
| `aem_logs` | error.log entries filtered by level, logger, text, request path or time. Reads the local file when available, otherwise uses the Sling log tailer. |
| `aem_recent_requests` | The Recent Requests console and the Request Progress Tracker (timings, resolution, included scripts) of one request. |
| `aem_debug_toggle` | Debug-only OSGi changes: `wcm-debug-filter`, `recent-requests` (longer history), `rpt-log`, `log-level` for given loggers. `action=plan` shows the change; enabling needs the user's explicit confirmation. Every change is journaled and reverted automatically. |

## Rendering and components

The page tools take a page path with `instance=author|publish`, or a local URL. A URL of an AEM instance selects
that instance. Any other local origin is a front-end dev server: the page is rendered exactly as given and
debugged on the AEM instance it proxies to (`instance=` the first time; remembered). `session` chooses who the
browser is: `auto` (author: the AEM login; publish and dev servers: the site session if one was captured, else
anonymous), `aem`, `site` or `none`. A render never mixes two identities.

| Tool | What it does |
|---|---|
| `page_render` | Headless render (installed Edge/Chrome) with a screenshot, HTTP status, timings, failed requests and console errors. Author pages use `wcmmode=disabled` by default. When local requests of a publish or dev-server page are refused (401/403), it suggests the site login. |
| `page_component_map` | Maps the rendered page to components: numbered boxes (#n) on the screenshot, empty components, Sling Models and local model/HTL files. |
| `component_inspect` | One component in depth: JCR properties vs model JSON vs model fields, HTL script, policy, HTML fragment, cropped screenshot and related log lines. |
| `project_index` | Finds and indexes the local AEM project(s): modules, Sling Models (injections, resource types), components, HTL scripts and their use-objects, servlets, OSGi config files, client libraries. `workspace=<folder>` uses the chat's folder; `projectDir=<folder>` adds a project and remembers it for this AEM (`replace`, `remove` manage the list). Aggregator repositories and several repositories are supported. When logged in, it shows which projects' bundles are installed. |

## JVM (JDWP)

The JDWP port accepts **one** debugger. Detach IntelliJ's "Remote JVM Debug" session before using these tools.
The server attaches on demand and detaches after `jvm_connection action=detach`, after `debug_cleanup`, or
after inactivity. Breakpoints never pause AEM: the hit thread is held only while its stack, locals and `this`
are recorded, then resumed.

Author and publish are separate JVMs. Every `jvm_*` tool takes `instance` (default author); breakpoints, watches
and snapshots belong to that instance's JVM. Publish needs its own JDWP port (`AEM_PUBLISH_JDWP_PORT`, or
`jvm_connection port=` for the current chat).

| Tool | What it does |
|---|---|
| `jvm_connection` | `attach`, `detach` or `status` of the JDWP connection of an instance (author: `AEM_JDWP_HOST`:`AEM_JDWP_PORT`, default 127.0.0.1:8767; publish: `AEM_PUBLISH_JDWP_PORT`). `port` overrides the port for the current chat. |
| `jvm_breakpoint` | Snapshot breakpoints and logpoints. Location: `com.mysite.Foo:41`, `Foo.java:41` (resolved through the local project) or `com.mysite.Foo#method`. Options: `condition` (`title == null && count > 2`; operators `== != < > contains startsWith endsWith matches && || !`), `log` (logpoint message, e.g. `title={title}`), `request` (URI text, `^regex`, or `requests` for any request), `maxHits` (default 50, then the breakpoint disables itself). Breakpoints are re-armed when a bundle is redeployed. |
| `jvm_exception_watch` | Captures exceptions at the throw site, including caught and swallowed ones, with stack, locals and the Sling request. Default scope: the project's packages plus `org.apache.sling.models` and `org.apache.sling.scripting.sightly`. |
| `jvm_snapshots` | Lists (filter by source, request, kind or age), shows (`get`), expands objects from (`expand`) and clears captured snapshots. |
| `jvm_classes` | Every loaded version of a class with its bundle and revision, compared with the local build's line tables: tells you whether AEM runs your current code. |

Condition and logpoint expressions are evaluated by the server on the captured values. No code runs inside AEM
for them. A few side-effect-free getters of well-known AEM types (Resource, ValueMap) may be invoked with a short
timeout to render values.

## One-call workflows

| Tool | What it does |
|---|---|
| `debug_page` | Render + component map + server-side capture in one call. It arms a temporary exception watch (and optional `breakpoints`, for example `"HeroModel.java:41 if title == null"`), marks error.log and the Recent Requests console, renders the page, and matches exceptions, snapshots, log lines and RPT entries to the browser's requests via the Sling request thread name. Returns a Debug Report with hints, an annotated screenshot and artifacts (`page.html`, screenshots, `components.json`, `error-log.txt`, `rpt.txt`, `snapshots.json`, `report.md`). For a dev-server page the server side comes from the instance behind it and a "Dev server" section lists the requests that reached AEM; components are mapped only with `components=true`. |
| `debug_capture` | `start` / `stop` / `status`: the same server-side capture while the user reproduces a problem in their own browser or the author UI; `stop` groups everything per Sling request. |
| `debug_cleanup` | Ends the session: stops a running capture, removes breakpoints and watches, detaches JDWP, reverts debug toggles and closes the headless browser. |

## How requests are correlated

Sling names each request thread `<remoteAddr> [<startMillis>] <METHOD> <URI> <protocol>` while it processes the
request (the URI has no query string). The same name appears in error.log as `[thread]` and in JDI as the
thread name. `debug_page` joins browser requests to server requests by method and path, using the closest start
time within 5 seconds (browser and AEM share the local clock). Request Progress Tracker entries carry no start
time, so they are matched by method, path and order. Events from other clients and from the server's own reads
(for example the edit-mode render used for component mapping) are listed separately. Events from background
threads go to "Not on a request thread".

Matching uses method and path only, not the host, so requests a front-end dev server proxies to AEM unchanged
match the AEM instance's request threads. A proxy that rewrites paths prevents the match.
