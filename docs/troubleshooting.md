# Troubleshooting

## Local project

**"No local AEM project is known" or results without source file links**
The server cannot see which folder a chat runs in. Tell Copilot where the project is ("my AEM project is at
C:\code\mysite"); it calls `project_index projectDir=<folder>`, and the folder is remembered for that AEM. A chat
opened inside the project finds it through `project_index workspace=<folder>`. See [setup](setup.md#local-project).

**"NOT deployed here" in `aem_status` or `project_index`**
None of the bundles built by that folder is installed in AEM. Either the folder is wrong (another project or
branch), or the project is not deployed on this instance. Use `project_index remove=<folder>` to stop using it.

## JVM debugging

**"... was started without JDWP"**
The JVM that serves the instance's port has no JDWP agent, so no debugger can attach. Restart that instance with
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=<port>` (the message suggests a free port; any free
port works and is detected). Publish needs a port different from author's. Everything except JVM capture works
without JDWP.

**"Another debugger is connected to the ... JVM"**
JDWP accepts a single debugger, and while one is connected the agent doesn't listen. It is IntelliJ's "Remote JVM
Debug" session, or aem-debug in another chat; that one detaches on `debug_cleanup` there or after
`AEM_DEBUG_IDLE_DETACH_MINUTES` without activity. Stop the other debugger, then retry. When you are done,
`jvm_connection action=detach` or `debug_cleanup` hands the port back to IntelliJ.

**"... has a JDWP agent that does not accept debuggers"**
The JVM's JDWP agent connects out to a debugger instead of listening (`server=n`): typically an IDE started AEM in
debug mode and is its debugger. To let aem-debug attach, start AEM with
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=<port>` in place of that option, not in addition (a
JVM loads only one JDWP agent). IntelliJ can then attach with a "Remote JVM Debug" configuration when the agent is
free.

**"JDWP port ... refused the connection"**
The port was not detected from the JVM (the message says why, for example AEM runs in a container, in WSL or as
another user), so the configured `AEM_JDWP_PORT` / `AEM_PUBLISH_JDWP_PORT` or a port set with `jvm_connection port=`
was tried. Either another debugger is attached, or the instance does not listen for debuggers on that port. Set the
right port, or `jvm_connection port=0` to forget a port given in the chat. Right after a detach the JVM needs a
moment to reopen its port; the server retries briefly.

**The JDWP port is not detected**
`aem_status` shows why after "not detected:". Detection reads the JDWP agent's address through the JDK's Attach
API, which needs a full JDK for the server and an AEM JVM of the same OS user. It cannot see AEM in a container,
in WSL or under another user, or a JVM started with `-XX:+DisableAttachMechanism`. On Linux and macOS it skips JVMs
started with `-Xrs`. `AEM_DEBUG_DETECT_JDWP=false` turns detection off. In all these cases the configured ports are
used. See [JDWP port detection](setup.md#jdwp-port-detection).

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

**Publish snapshots never appear**
Publish runs in its own JVM. `aem_status` says whether it was started with JDWP; if not, restart it with a port of
its own, for example `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8768`. Breakpoints, watches and
snapshots are per instance: pass `instance=publish` to the `jvm_*` tools.

## Login and access

**A browser window opened on the AEM login page**
That is how the server gets access: sign in there (local admin or SSO) and retry the tool. The login token is
remembered for later chats in an owner-only file (`AEM_DEBUG_REMEMBER_LOGINS=false` keeps it in memory only). To
skip the window on a local SDK you can set `AEM_USER` and `AEM_PASSWORD` in the MCP server's environment (never
in the chat).

**"login pending" keeps coming back**
The login window was closed or timed out. Call `aem_auth action=login` and complete the login in the window.
Several tools waiting at the same time share one window per instance.

**I have to sign in again in a new chat**
Remembered logins expire after 7 days and are dropped when AEM rejects the token (for example after AEM was
reinstalled). Check `aem_auth action=status`: it says where logins are remembered, or that they are kept in memory
only.

**HTTP 401/403 in page renders**
On author, check `aem_auth action=status`. On publish or a dev server, the page or its API calls usually need a
signed-in visitor: the report suggests `aem_auth action=site-login url=<page>`. Sign in in that window, close it,
and render again; publish and dev-server renders use the site session automatically. Use `session=aem` when the
calls need the AEM login of publish instead. On publish the page may also need to be activated, or the path may
be blocked.

**The site session does not work in renders**
Close the site login window only when the site shows you signed in; `aem_auth action=status` lists the captured
cookie names and storage keys. Sessions that live only in the page's memory (not in cookies or storage) cannot be
carried over. Sign in again with `aem_auth action=site-login` when the site session has expired.

## Rendering

**"... is not one of the configured AEM instances"**
The URL belongs to a front-end dev server. Pass `instance=publish` or `instance=author` once (the AEM it proxies
to; see its proxy configuration); it is remembered. A local URL is never rewritten to another origin.

**A dev-server page shows no components and no RPT for the document**
The dev server answered the page URL with its own app shell, so AEM did not render the document. The report's
"Dev server" section lists the requests that did reach AEM. If the dev server proxies the page itself, pass
`components=true`.

**No server-side data for a dev server's API calls**
The calls are matched by method and path. A proxy that rewrites paths (for example `/api` to `/services/api`)
prevents the match; so does a dev server that answers them itself.

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
`AEM_LOG_DIR` (author) or `AEM_PUBLISH_LOG_DIR` (publish) to the `crx-quickstart/logs` folder. Without file
access the server falls back to the Sling log tailer in the Felix console (needs admin login).

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
