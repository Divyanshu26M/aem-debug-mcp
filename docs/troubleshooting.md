# Troubleshooting

## JVM debugging

**"JDWP port ... refused the connection" or `aem_status` says the port is not accepting connections**
- Another debugger is attached. JDWP accepts a single debugger. Stop IntelliJ's "Remote JVM Debug" session (or
  any other debugger), then retry. When you are done, `jvm_connection action=detach` or `debug_cleanup` hands the
  port back to IntelliJ.
- AEM was not started with JDWP. Start it with
  `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8767` (or set `AEM_JDWP_PORT` to your port).
- Right after a probe or a detach the JVM needs a moment to reopen the port. The server retries briefly; if it
  still fails, call `jvm_connection action=attach` again.

**"JVM debugging is not available" (`jdk.jdi` missing)**
The server runs on a JRE or a trimmed runtime. Run it with a full JDK 17+ (`JAVA_HOME` pointing to a JDK).

**A breakpoint stays "pending"**
The class is not loaded yet; it arms itself when AEM first uses the class. Render a page that uses it. If it
never arms, check the class name (`jvm_classes`), that the bundle is active (`aem_osgi_status`), and for
`File.java:line` locations that the file belongs to the indexed project (`project_index`).

**A breakpoint reports a problem such as "no code at line N"**
The line has no executable code in the class that AEM runs (a blank or comment line, or AEM runs an older build).
Choose a statement line; `jvm_classes` shows whether the deployed class matches your local build.

**A breakpoint disabled itself**
It reached `maxHits` or fired too often per second (flood protection keeps AEM responsive). Narrow it with
`condition` or `request` and set it again.

**The server detached on its own**
It detaches after `AEM_DEBUG_IDLE_DETACH_MINUTES` (default 15, 0 = never) of inactivity so IntelliJ can
reconnect. Long `debug_capture` sessions can be cut short by this.

## Login and access

**A browser window opened on the AEM login page**
That is how the server gets access: sign in there (local admin or SSO) and retry the tool. The session cookie
is kept in memory only. To skip the window on a local SDK you can set `AEM_USER` and `AEM_PASSWORD` in the MCP
server's environment (never in the chat).

**"login pending" keeps coming back**
The login window was closed or timed out. Call `aem_auth action=login` and complete the login in the window.

**HTTP 401/403 in page renders**
The browser session has no access to the page. Check `aem_auth action=status`; on publish, the page may need to
be activated or the path may be blocked.

## Rendering

**No browser found / Playwright errors**
The server uses an installed Microsoft Edge or Google Chrome (headless) and does not download browsers. Install
one of them or set `AEM_DEBUG_BROWSER_CHANNEL` (`msedge`, `chrome` or `chromium`). On unusual platforms set
`PLAYWRIGHT_NODEJS_PATH` to a local Node.js if the bundled driver cannot start.

**Components are "not found" in the page map**
Mapping uses Core Components ids, edit-mode markup, WCM debug comments and class order. Custom components that
render no wrapper element are hard to locate. `aem_debug_toggle toggle=wcm-debug-filter` (with the user's
consent) adds debug comments that improve mapping on author.

## Logs and Request Progress Tracker

**error.log is "not readable"**
The server finds the log through sling.home. If AEM runs in a container or under another user, set
`AEM_LOG_DIR` to the `crx-quickstart/logs` folder. Without file access the server falls back to the Sling log
tailer in the Felix console (needs admin login).

**No RPT for the document request**
The Recent Requests console keeps only the last 20 requests by default, and a page with many assets pushes the
document out before it can be read. With the user's consent: `aem_debug_toggle toggle=recent-requests`.

## Server

**Where are the logs and artifacts?**
`aem_status` prints the artifacts directory and the server log file. The server never writes to stdout except MCP
messages.

**Changes reverted after a crash**
Debug toggles are journaled. If the server stopped unexpectedly, the remaining toggles are reverted on the next
start, once a login session exists.
