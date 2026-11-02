# Why aem-debug-mcp

aem-debug-mcp gives your AI assistant the four views of a local AEM bug: the rendered page, the AEM components and
content behind it, the logs of that request, and the JVM state. All four are joined per HTTP request. It plugs into
GitHub Copilot, Claude Code or another MCP client; it doesn't replace them.

- [The problem](#the-problem)
- [What aem-debug-mcp adds](#what-aem-debug-mcp-adds)
- [Use cases](#use-cases)
- [How it compares](#how-it-compares)
- [Safety: why the limits matter when an agent drives](#safety-why-the-limits-matter-when-an-agent-drives)

## The problem

A local AEM bug rarely shows up in one place. To find out why a component renders empty, a developer typically:

1. opens the page in the browser and its developer tools: what does it show, which requests failed, which errors are
   in the console?
2. finds the component's resource in CRXDE Lite and compares its properties with what the Sling Model expects;
3. checks bundles, components and configurations in the Felix web console;
4. tails error.log and tries to pick out the lines of this page load among everything else AEM logs;
5. opens Recent Requests to see which scripts ran, if the request is still among the last 20 it keeps;
6. attaches IntelliJ IDEA's Remote JVM Debug, sets a breakpoint, reloads the page and reads variables while AEM
   waits.

AI assistants help with the code, but out of the box they see your files and a terminal. Browser tools now let them
see a page, and IntelliJ IDEA can lend them its debugger, but nothing connects what the page shows to the AEM
component, content, logs and JVM state behind it. So the developer becomes the bridge: pasting screenshots, log
excerpts and variable values into the chat, and describing what the browser shows. aem-debug-mcp started from that
observation: the agent should be able to look for itself.

## What aem-debug-mcp adds

Four views of the same problem, collected by the agent itself:

| View | What the agent gets | Tools |
|---|---|---|
| The rendered page | A headless Edge or Chrome render: screenshot, HTTP statuses, failed requests, console errors | `page_render`, `debug_page` |
| The components and content behind it | Each part of the page mapped to its component (numbered #n on the screenshot), with its resource, Sling Model, HTL script and local source files; read-only JCR content, page context, OSGi configuration and bundle health | `page_component_map`, `component_inspect`, `aem_get_resource`, `aem_page_context`, `aem_get_osgi_config`, `aem_osgi_status`, `project_index` |
| The logs of that request | The error.log lines and the Request Progress Tracker of the page's own requests | `debug_page`, `aem_logs`, `aem_recent_requests` |
| The JVM state | Exceptions where they are thrown, including swallowed ones, and snapshot breakpoints that record the stack, locals and `this` while AEM keeps running | `jvm_exception_watch`, `jvm_breakpoint`, `jvm_snapshots`, `jvm_classes` |

**Joined per HTTP request.** Sling names each request thread after its request, for example
`127.0.0.1 [1730540000000] GET /content/wknd/us/en.html HTTP/1.1`. The same name appears in error.log and in the
JVM. So `debug_page` matches exceptions, snapshots, log lines and Request Progress Tracker entries to the browser
requests of one page load, by method, path and start time. It sets aside what other tabs, the author UI or
background jobs did meanwhile ([details](tools.md#how-requests-are-correlated)).

**A tool for your assistant, not another assistant.** aem-debug-mcp is an MCP server. It ships as a GitHub Copilot
plugin, with a skill that teaches the debugging loop and a diagnose-only `aem-debugger` agent. It also runs as a
standalone jar in any MCP client: [setup](setup.md) has snippets for VS Code and IntelliJ IDEA, and Claude Code adds
it with `claude mcp add`. So far it has been used with GitHub Copilot; other clients speak the same protocol but
haven't been tried yet.

It is built for a local AEM as a Cloud Service SDK or AEM 6.5 (verified so far on the SDK), author and publish, and
for front-end dev servers that proxy to them. It finds each instance's JDWP port by itself
([details](setup.md#jdwp-port-detection)).

## Use cases

Each scenario shows the symptom, what you might ask, what the agent does and what you get. The examples use the
WKND sample site and a `com.mysite` project; the tools work the same on yours.

A status line says how far each scenario has been verified on a real AEM (see the README's
[Status and limits](../README.md#status-and-limits)). "Covered by automated tests" means tests against a test JVM or
a stub AEM, not yet a real page.

### 1. A component renders empty or wrong

- **Symptom:** the teaser on `/content/wknd/us/en` shows no title.
- **You ask:** "The teaser on /content/wknd/us/en has no title. Find out why."
- **The agent:** runs `debug_page path=/content/wknd/us/en`. In one call it renders the page, numbers its
  components, watches for exceptions in Sling Models and HTL, and collects the log lines of the page's requests.
  Then `component_inspect` on the teaser compares its JCR properties, the model JSON and the Sling Model's
  injected fields.
- **You get:** the teaser flagged as rendered empty (#4 on the screenshot), its Sling Model
  (`com.mysite.core.models.TeaserModel`) and HTL script, and a hint such as "JCR has 'jcr:title' but field 'title'
  injects 'title'" that points at the line to fix.
- **Status:** rendering, component mapping and `component_inspect` were verified on a local AEM author. Linking
  components to local source files isn't verified on a real page yet.

### 2. A page returns 500

- **Symptom:** `/content/wknd/us/en/adventures.html` shows the error page.
- **You ask:** "This page returns 500 on my local author. What's failing?"
- **The agent:** runs `debug_page`, which records the document request's status, the exception at the throw site
  with its stack and local variables (when the JVM is attached), and the error.log lines written by that request.
  `aem_resolve` shows which script or servlet handles the URL.
- **You get:** the exception and log lines of exactly that request, separated from everything else in the log, and
  the class and line that failed.
- **Status:** matching error.log lines to the page's own requests was verified on a local AEM author; it surfaced
  a Sling Model error on the document request. Exception snapshots in project code and `aem_resolve` aren't verified
  on a real page yet.

### 3. Styles or scripts are missing (clientlib 404)

- **Symptom:** the page renders without the site's CSS.
- **You ask:** "The site styles don't load on /content/wknd/us/en."
- **The agent:** runs `page_render`, which lists the failed requests, for example a 404 on
  `/etc.clientlibs/wknd/clientlibs/clientlib-site.min.css`, and the console errors. `project_index show=clientlibs`
  lists the project's client libraries with their categories and whether they allow the `/etc.clientlibs` proxy.
- **You get:** the failing URL and the likely cause: a client library without `allowProxy`, a wrong category, or a
  library that isn't deployed.
- **Status:** `page_render` and the project index were verified on a local AEM author and a real project.

### 4. A change doesn't show up

- **Symptom:** you fixed `HeroModel` and redeployed, but the page still behaves the old way.
- **You ask:** "I deployed my fix to HeroModel but nothing changed. Is my code running?"
- **The agent:** runs `jvm_classes class=com.mysite.core.models.HeroModel`. It lists every loaded version of the
  class with its bundle and revision, and compares its line tables with your local build in `target/classes`.
  `aem_osgi_status` shows bundles that didn't resolve after the deploy, and `project_index` shows whether the
  project's bundles are installed at all.
- **You get:** one of "AEM runs your local build", "the bundle in AEM is stale" or "an old bundle revision is still
  wired", with the next step.
- **Status:** listing loaded class versions with their bundle was verified on the AEM SDK. The comparison with a
  local build is covered by automated tests.

### 5. An OSGi configuration isn't applied

- **Symptom:** you added a configuration for `com.mysite.core.services.impl.ApiClientImpl`, but the service still
  uses its defaults.
- **You ask:** "Is my ApiClient OSGi configuration applied on author?"
- **The agent:** runs `aem_get_osgi_config pid=com.mysite.core.services.impl.ApiClientImpl`, which shows the
  configuration AEM actually uses next to the matching configuration files in your project. `aem_status` shows the
  instance's run modes, and `aem_osgi_status` whether the component is active.
- **You get:** the effective values side by side with your files, so a wrong run-mode folder, PID or value stands
  out.
- **Status:** `aem_get_osgi_config`, `aem_osgi_status` and `aem_status` were verified on a local AEM author.

### 6. An exception is swallowed

- **Symptom:** a component renders partly, and error.log shows nothing.
- **You ask:** "The map on /content/wknd/us/en/adventures is empty and nothing is logged. What's going on?"
- **The agent:** arms `jvm_exception_watch` (`debug_page` also arms a temporary one by itself). It captures
  exceptions at the moment they are thrown in project code, Sling Models and HTL, even when a `try/catch` swallows
  them, with the stack, local variables and the Sling request that threw them.
- **You get:** the hidden exception, for example a `NullPointerException` in `com.mysite.core.models.MapModel`, with
  the values that caused it, tied to the page's request.
- **Status:** exception capture is covered by automated tests. On a real AEM page, capture in project code isn't
  verified yet.

### 7. A value is wrong only for some requests

- **Symptom:** prices are wrong on the Canadian French pages only.
- **You ask:** "Prices on the /content/wknd/ca/fr pages are wrong. Capture the price calculation for those requests
  only."
- **The agent:** sets a snapshot breakpoint with a condition and a request filter:
  `jvm_breakpoint location=com.mysite.core.models.PriceModel:57 condition="currency == null" request=/content/wknd/ca/fr`.
  Each matching hit records the stack, locals and `this`, and the thread continues. The agent reloads the page with
  `debug_page` (or you browse) and reads the values with `jvm_snapshots`.
- **You get:** the variable values of the failing requests only, while AEM keeps serving everyone else. Nothing
  waits on a paused thread, and there is no race between pausing and reproducing.
- **Status:** snapshot breakpoints were verified on the AEM SDK with a Sling engine method. Conditions and
  breakpoints in project code are covered by automated tests, not yet on a real page.

### 8. A problem only on publish

- **Symptom:** `/content/wknd/us/en/faqs` looks right on author and broken on publish.
- **You ask:** "/content/wknd/us/en/faqs is fine on author but broken on publish. Compare them."
- **The agent:** renders the page with `instance=publish`, as an anonymous visitor by default, and reads it with
  `aem_get_resource` on both instances. `aem_page_context` on author shows when the page was last replicated.
  `debug_page instance=publish` adds publish's error.log lines and, when publish runs with JDWP, its exceptions.
  `aem_status` says when it doesn't.
- **You get:** the difference: content that wasn't replicated, a component that fails only for anonymous visitors,
  or an exception that happens only on publish.
- **Status:** page renders and JDWP port detection were checked on a local publish. Publish JVM capture isn't verified
  on a real page yet.

### 9. A front-end dev server in front of AEM

- **Symptom:** the React app on `http://localhost:3000` shows an error on its account page. Its API calls are proxied
  to publish.
- **You ask:** "The account page on localhost:3000 shows an error. The dev server proxies to publish."
- **The agent:** runs `debug_page path=http://localhost:3000/account instance=publish`. It renders the dev server's
  page as given and reads the server side from publish. The report's "Dev server" section lists the calls that
  reached publish, with their error.log lines and, when publish runs with JDWP, their exceptions. If calls answer
  401 or 403, the agent opens `aem_auth action=site-login`, so you can sign in as a site visitor, including through an
  external identity provider. Then it renders again with that session.
- **You get:** which API call failed, and why, from the AEM side, without leaving the chat. The dev server's mapping
  to publish is remembered; the site session stays in memory only.
- **Status:** checked against a local React dev server that proxies to publish. The site login window isn't verified
  with a real identity provider yet.

### 10. Reproduce it yourself: your browser or the author UI

- **Symptom:** saving a component dialog in the page editor fails, or a problem only happens in your own browser
  session.
- **You ask:** "Saving the teaser dialog fails. I'll reproduce it; watch the server."
- **The agent:** runs `debug_capture action=start`, which arms a temporary exception watch (and optional
  breakpoints) and marks error.log and Recent Requests. You reproduce it in your browser, then the agent runs
  `debug_capture action=stop`, which groups everything that happened per Sling request.
- **You get:** the request that failed, for example the dialog's POST, with its exception, log lines and Request
  Progress Tracker, separated from the editor's other requests.
- **Status:** not verified on a real page yet. It uses the same capture code as `debug_page`.

### 11. Hand the investigation to the aem-debugger agent

- **Symptom:** you want the cause and the fix, not every screenshot and log line in your main conversation.
- **You ask** (in GitHub Copilot): "Use the aem-debugger agent to find out why the hero on /content/wknd/us/en is
  empty." You can also select it with `/agent` or start `copilot --agent aem-debug-mcp:aem-debugger`.
- **The agent:** a separate, diagnose-only agent runs the loop above. It can use every debug tool except
  `aem_debug_toggle`, and read and search code. It can't edit files, run commands, deploy or change settings.
- **You get:** a short report with the diagnosis, the evidence (component, snapshot ids, log lines, report id), the
  fix (file and line), how to verify it, and what it couldn't check. Your main Copilot session then makes the fix.
- **Status:** the agent's tool list is checked by an automated test and was checked in GitHub Copilot CLI.

## How it compares

*As of November 2026; these tools change quickly.*

aem-debug-mcp is a tool for your assistant, not another assistant. GitHub Copilot or Claude Code stays your
assistant. On its own, an assistant reads and edits your code and runs a terminal. Given credentials, it can curl
AEM's JSON and consoles and grep error.log, but by hand and not tied to a page render. The table compares what you
can add to it.

✅ yes · ⚠️ partly · ❌ no · ❔ not in the documentation

| Capability | Browser tools¹ | IntelliJ IDEA agentic debugging² | mcp-jdwp-java³ | Adobe AEM MCP server⁴ | Community AEM MCP servers⁵ | Apache Sling MCP server⁶ | aem-debug-mcp |
|---|---|---|---|---|---|---|---|
| See the rendered page: screenshot, console errors, failed requests | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Map the rendered page to its AEM components | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Go from a component to its Sling Model, HTL and local source files | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅† |
| Read content: resources, templates, policies | ❌ | ❌ | ❌ | ⚠️ pages, fragments, assets | ✅ | ❌ | ✅ read-only |
| Check bundles, OSGi components and configurations | ❌ | ❌ | ❌ | ❌ | ⚠️ one server | ⚠️ bundles and components | ✅ |
| Get the error.log lines of this page's own requests | ❌ | ❌ | ❌ | ❌ | ⚠️ log search | ⚠️ recent logs | ✅ |
| Get the Request Progress Tracker of this page's own requests | ❌ | ❌ | ❌ | ❌ | ❌ | ⚠️ recent requests | ✅† |
| Catch exceptions where they are thrown, including swallowed ones | ❌ | ❔ | ✅ | ❌ | ❌ | ❌ | ✅† |
| Read variable values without pausing AEM | ❌ | ⚠️ breakpoints pause | ✅ logpoints | ❌ | ❌ | ❌ | ✅ snapshots |
| Join all of the above per HTTP request | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Where it works | Any page | JVMs the IDE debugs | Any JVM with JDWP | AEM as a Cloud Service | Local AEM; one also AEM as a Cloud Service | Sling Starter | Local AEM SDK or 6.5, author and publish |
| Can change AEM or its JVM | ⚠️ through the UI it clicks | ⚠️ sets variables | ⚠️ changes values, runs code | Yes: content, assets, pipelines | Yes: content, workflows; one also OSGi and scripts | No | No, read-only‡ |

1. VS Code's built-in browser tools, Claude in Chrome (for Claude Code), Playwright MCP or Chrome DevTools MCP. They
   see any page from the browser side, AEM's included.
2. IntelliJ IDEA 2026.1.3 and later expose the debugger to agents over the IDE's MCP server. The agent drives the
   standard debugger, so a breakpoint pauses the thread until the agent resumes it. Exception breakpoints and
   non-suspending breakpoints aren't among the documented agent tools.
3. A general-purpose JDWP debugger for agents, installed as a Claude Code plugin.
4. Adobe's unified AEM MCP server for AEM as a Cloud Service: content, Cloud Manager, Experience Governance and Cloud
   Migration tools.
5. easingthemes/aem-mcp-server, indrasishbanerjee/aem-mcp-server and arkadiusz-rosiak/aem-dev-mcp. The content
   servers can list a page's components from the repository, not from the rendered page. The OSGi tools and log
   search come from aem-dev-mcp.
6. Runs inside Sling at `/bin/mcp`. Documented with the Sling Starter Docker image; its page doesn't cover AEM.

† Implemented and covered by automated tests, but not verified on a real AEM page yet (see the README's
[Status and limits](../README.md#status-and-limits)).

‡ The one exception: debug-only OSGi settings, changed only after you confirm the exact change, and reverted
automatically.

### The tools in more detail

**GitHub Copilot.** aem-debug-mcp installs as a Copilot plugin: the server, the `aem-local-debugging` skill and the
diagnose-only `aem-debugger` agent. In VS Code, Copilot's agents also have
[built-in browser tools](https://code.visualstudio.com/docs/agents/run/browser-tools) that open pages, take
screenshots and read console errors, also on a page you share with your sign-in. aem-debug-mcp adds the AEM side of
the same page. Adobe's AEM MCP server and Adobe's skills also work with Copilot, so all of them can be used side by
side.

**Claude Code.** Claude Code is an MCP client, so it can run aem-debug-mcp as a local stdio server added with
[`claude mcp add`](https://code.claude.com/docs/en/mcp). That setup hasn't been tried yet, and the plugin's
`aem-debugger` agent is specific to Copilot. [Claude in Chrome](https://code.claude.com/docs/en/chrome) gives Claude
Code the browser side: console errors and DOM state in a visible Chrome or Edge window that shares your sign-in
state. It needs the extension and a direct Anthropic plan. For a debugger, Claude Code can use IntelliJ IDEA's
agentic debugging or mcp-jdwp-java.

**Browser tools.** [VS Code's browser tools](https://code.visualstudio.com/docs/agents/run/browser-tools),
[Claude in Chrome](https://code.claude.com/docs/en/chrome), [Playwright MCP](https://github.com/microsoft/playwright-mcp)
and [Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp) let an agent open pages, take
screenshots and read console errors. Playwright MCP and Chrome DevTools MCP also list network requests, and Chrome
DevTools MCP analyzes performance. They see AEM pages like any other page. aem-debug-mcp renders the same view in a
headless browser and adds what AEM did for those requests.

**IntelliJ IDEA agentic debugging.** Since IntelliJ IDEA 2026.1.3, the IDE's MCP server exposes its debugger to
agents such as Claude Code and Codex, as tools prefixed `xdebug_`
([docs](https://www.jetbrains.com/help/idea/agentic-debugging.html)). An agent can start a session for a run
configuration (for AEM, a Remote JVM Debug configuration), set breakpoints, step, read variables and evaluate
expressions. It drives the standard debugger, so the agent waits for a breakpoint to pause the thread, and the
request waits meanwhile. It's the right tool for stepping through code. aem-debug-mcp detaches first, because AEM
accepts one debugger at a time.

**mcp-jdwp-java.** [mcp-jdwp-java](https://github.com/FgForrest/mcp-jdwp-java) is a general-purpose JDWP debugger for
agents, installed as a Claude Code plugin. Its 50 tools include exception breakpoints at the throw site, logpoints
that let the thread run on, evaluating Java in a suspended frame and changing values. It is the closest JVM-side
tool. It works with any JVM, and doesn't know about HTTP requests, pages or AEM. Its JDI patterns were a reference
when aem-debug-mcp's own engine was built.

**Adobe's AEM MCP server.** Adobe's
[unified AEM MCP server](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/ai-in-aem/mcp-support/using-mcp-with-aem-as-a-cloud-service)
(`https://mcp.adobeaemcloud.com/adobe/mcp/aem`) serves AEM as a Cloud Service. Its tool areas:

- Content: pages, content fragments and assets.
- Cloud Manager: programs, environments, repositories and pipelines.
- Experience Governance: brand checks, with the agents trial or a paid license.
- Cloud Migration: Best Practices Analyzer findings.

It runs as the signed-in user, with that user's AEM permissions, and replaces Adobe's earlier domain-specific MCP
servers, which are deprecated. Its documentation covers cloud environments, not a local SDK, and its tool areas don't
include rendering pages, request logs or JVM debugging. The two complement each other: Adobe's server for cloud
content and pipelines, aem-debug-mcp for what your code does at runtime on your machine. For Edge Delivery Services,
Adobe's [Experience Workspace MCP](https://www.aem.live/docs/ew/authoring/mcp) works with Experience Workspace
content.

**Adobe's skills.** [Adobe's skills](https://github.com/adobe/skills) are plugins for Claude Code that GitHub Copilot
CLI can install too: for example `aem-cloud-service`, `aem-6-5-lts` and `aem-edge-delivery-services`. They cover
component development, Dispatcher, workflows, code assessment, migration and more. They guide how the agent writes
and reviews AEM code. aem-debug-mcp shows what that code does at runtime, so they work well together.

**Community AEM MCP servers.** They focus on content and administration; their documentation doesn't cover rendering
pages or debugging the JVM:

| Server | Focus | Targets |
|---|---|---|
| [easingthemes/aem-mcp-server](https://github.com/easingthemes/aem-mcp-server) | 57 tools for pages, components, assets, content and experience fragments, workflows and replication, including changes | Local AEM with a user and password, or AEM as a Cloud Service with OAuth server-to-server |
| [indrasishbanerjee/aem-mcp-server](https://github.com/indrasishbanerjee/aem-mcp-server) | AEM 6.5 Author HTTP APIs: page commands, QueryBuilder, assets, replication and workflows; marked "pre-production" | AEM 6.5 Author SP17 or later |
| [arkadiusz-rosiak/aem-dev-mcp](https://github.com/arkadiusz-rosiak/aem-dev-mcp) | OSGi bundles, components and configurations (including changes), log search, Groovy scripts, health checks; last commit September 2025 | Instances listed in a local configuration file |

**Apache Sling MCP server.** [Apache Sling's MCP server](https://sling.apache.org/documentation/bundles/mcp-server.html)
runs inside a Sling instance at `/bin/mcp`, with admin-only Basic authentication. It has three tools: `logs` (recent
logs, filtered), `recent-requests` (with Request Progress Tracker output) and `diagnose-osgi-bundle`. It is meant
for development and diagnostics and is documented with a Sling Starter snapshot Docker image; its page doesn't cover
AEM. It shares part of aem-debug-mcp's server-side view, but doesn't tie logs or requests to a page you rendered, and
has no browser or JVM tools.

**Classic AEM tools.** CRXDE Lite, the Felix web console, the
[Recent Requests console](https://sling.apache.org/documentation/development/monitoring-requests.html) (the latest 20
requests with their Request Progress Tracker), [Sling Log Tracer](https://sling.apache.org/documentation/bundles/log-tracers.html)
(which can record the logs of a single request) and IntelliJ IDEA's Remote JVM Debug remain the ground truth.
aem-debug-mcp reads many of the same sources for the agent, and joins them per request. IntelliJ IDEA's debugger and
aem-debug-mcp share AEM's single JDWP connection, so only one of them is attached at a time.

### What's new

We haven't found another tool that does these things for AEM:

1. **One request, four views.** Exceptions, snapshots, error.log lines and Request Progress Tracker entries are
   joined to the browser request that caused them, using the Sling request thread name.
2. **From pixels to source.** The rendered page is mapped to its AEM components: numbered on the screenshot, empty
   ones flagged, each with its Sling Model, HTL script and local files.
3. **Snapshot debugging made for AEM.** Exception watches on project code, Sling Models and HTL are armed only while
   the page renders. Breakpoints take conditions and request filters. The stack, locals and `this` are recorded, and
   AEM never pauses. The agent triggers the request itself, so there is no race between pausing and reproducing.
4. **Dev servers in front of AEM.** The browser side comes from the dev server, for example a React app on port
   3000, and the server side from the AEM instance it proxies to, with a site visitor login kept in memory.
5. **"Is my change deployed?"** `jvm_classes` compares the class running in AEM with your local build, and
   `aem_status` checks whether your project's bundles are installed.
6. **JDWP without setup.** The JDWP port is read from the JVM that serves each instance. It also tells you whether
   an instance was started without JDWP or another debugger is connected.
7. **A safety envelope for agents.** See [Safety](#safety-why-the-limits-matter-when-an-agent-drives).

Each piece has a precedent: browser tools see the page, Sling's Recent Requests and Log Tracer record one request's
server side, and IntelliJ IDEA and mcp-jdwp-java debug the JVM. What's new is having them together for the agent,
joined per request.

### When to use something else, or alongside

| Need | Use | With aem-debug-mcp |
|---|---|---|
| Cloud environments: dev, stage, production | Adobe's AEM MCP server (content and Cloud Manager tools), Cloud Manager | aem-debug-mcp stays local by design |
| Creating or changing content, or bulk edits | Adobe's AEM MCP server on AEM as a Cloud Service; a community content server on a local instance | aem-debug-mcp only reads content |
| Stepping through code, or changing values while paused | IntelliJ IDEA, yourself or through its agentic debugging; mcp-jdwp-java | Detach aem-debug-mcp first (`jvm_connection action=detach`); JDWP accepts one debugger |
| Writing components, Dispatcher configuration, workflows, migrations | Adobe's skills | Then check the result at runtime with `debug_page` |
| Edge Delivery Services content | Experience Workspace MCP, Adobe's `aem-edge-delivery-services` skills | Outside aem-debug-mcp's scope |
| Front-end-only issues: layout, console, performance | Browser tools such as Chrome DevTools MCP | aem-debug-mcp adds the AEM side when AEM serves the page |
| A plain Apache Sling application | Apache Sling's MCP server | aem-debug-mcp targets AEM |

### Sources

Checked on 2 November 2026:

- Adobe: [Using MCP with AEM as a Cloud Service](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/ai-in-aem/mcp-support/using-mcp-with-aem-as-a-cloud-service),
  [Experience Workspace MCP](https://www.aem.live/docs/ew/authoring/mcp), [Adobe skills](https://github.com/adobe/skills)
- Apache Sling: [MCP server](https://sling.apache.org/documentation/bundles/mcp-server.html),
  [Recent Requests](https://sling.apache.org/documentation/development/monitoring-requests.html),
  [Log Tracer](https://sling.apache.org/documentation/bundles/log-tracers.html)
- JetBrains: [IntelliJ IDEA agentic debugging](https://www.jetbrains.com/help/idea/agentic-debugging.html)
- Visual Studio Code: [browser tools for agents](https://code.visualstudio.com/docs/agents/run/browser-tools)
- Claude Code: [Chrome integration](https://code.claude.com/docs/en/chrome), [MCP](https://code.claude.com/docs/en/mcp)
- Browser MCP servers: [Playwright MCP](https://github.com/microsoft/playwright-mcp),
  [Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp)
- JVM: [mcp-jdwp-java](https://github.com/FgForrest/mcp-jdwp-java)
- Community AEM MCP servers: [easingthemes/aem-mcp-server](https://github.com/easingthemes/aem-mcp-server),
  [indrasishbanerjee/aem-mcp-server](https://github.com/indrasishbanerjee/aem-mcp-server),
  [arkadiusz-rosiak/aem-dev-mcp](https://github.com/arkadiusz-rosiak/aem-dev-mcp)

## Safety: why the limits matter when an agent drives

An agent chooses tools on its own and acts on text it reads: your prompt, but also page content, log lines and
repository data. The limits below keep the debugging loop safe even when the agent misreads a situation.

- **Read-only.** The tools read content, configuration, logs and JVM state. They don't create or change content,
  deploy, or change configuration. Breakpoint conditions and logpoint messages are evaluated by aem-debug-mcp on the
  captured values, not inside AEM. A wrong guess costs a tool call, not a broken instance. The one exception is
  `aem_debug_toggle`, for the WCM debug filter, a longer Recent Requests history, Request Progress Tracker logging
  and log levels. It shows the exact change first and applies it only after you confirm. Every change is journaled
  and reverted by `debug_cleanup`, after inactivity, when the server stops, or on the next start after a crash.
- **Local only.** The tools talk only to AEM instances and pages on `localhost`, `127.0.0.1` or `::1`, and refuse
  other hosts. So a pasted or injected link can't send the agent to a shared or production environment. Like any
  browser, a rendered local page still loads the external assets it references; `blockExternal=true` blocks them.
- **AEM never pauses.** A breakpoint that pauses a request thread holds that request, and the page waiting for it,
  until someone resumes it. An agent that runs out of time or context can leave it there. aem-debug-mcp doesn't
  pause:
  - A breakpoint records the stack, locals and `this`, then the thread runs on.
  - It never suspends the whole VM.
  - Breakpoints that fire too often disable themselves.
  - The JDWP connection is released on request, on `debug_cleanup` or after inactivity, so IntelliJ IDEA can
    reconnect.
  - Finding the JDWP port reads one property through the JDK's Attach API, without connecting a debugger.
- **Credentials stay out of the chat.** Chat transcripts, tool arguments and logs are stored and shared in ways
  passwords shouldn't be. You sign in in a visible browser window: as local admin, through SSO, or with a site's own
  login. The server keeps only the session token: AEM logins in an owner-only file (or in memory only, with
  `AEM_DEBUG_REMEMBER_LOGINS=false`), site sessions in memory only. No tool accepts a password as an argument, URLs
  with `user:password@` are refused, and tool output masks secrets.
- **A diagnose-only agent.** The `aem-debugger` agent in the Copilot plugin can use the debug tools and read code, but
  it can't edit files, run commands, deploy or toggle debug settings. It returns the cause and the fix; you decide.
