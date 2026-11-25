# Backlog

Future directions for aem-debug-mcp beyond the MVP, as of 25 November 2026. These are not available features or
delivery commitments: scope and priority may change, and no timelines are set. The key features come first, then the
open work and ideas. The last section compares aem-debug-mcp with the AEM MCP servers around it, which is where much
of the open work comes from.

- [Key features](#key-features)
- [Current boundary](#current-boundary)
- [Open work](#open-work)
- [Ideas, not decided](#ideas-not-decided)
- [Where aem-debug-mcp stands](#where-aem-debug-mcp-stands)

## Key features

| Item | Status | Scope |
|---|---|---|
| Interactive suspend/step debugging | Previously planned for a later phase; not implemented | Add interactive debugging beyond today's non-suspending snapshots, without leaving a request paused when the agent stops. That risk is why today's breakpoints never pause. |
| Companion OSGi bundle | Previously planned for a later phase; not implemented | Explore an optional AEM-side bundle for signals AEM doesn't provide out of the box. The MVP requires no companion bundle ([spike findings](spike-findings.md)); capabilities remain to be defined. |
| Cloud/server-side AEM debugging | Exploration only | Assess what is feasible through supported platform interfaces, subject to access controls and security constraints ([one possible shape](#ideas-not-decided)). This does not assume or promise cloud JDWP access. |

## Current boundary

aem-debug-mcp supports local AEM instances only; remote debugging is not supported today. This backlog does not
change the [safety model](../README.md#safety-model). See [Status and limits](../README.md#status-and-limits) for
current validation gaps, and [spike findings](spike-findings.md) for the MVP's technical evidence.

## Open work

Grouped, roughly in the order we would take it on.

### Verify what's implemented

Implemented, but not yet verified on a real AEM page or setup:

- breakpoint and exception snapshots in project code;
- the Request Progress Tracker of a page's requests (`aem_recent_requests`, also in `debug_page`);
- `debug_capture`, `aem_debug_toggle`, `aem_query` and `aem_resolve`;
- JVM capture on publish, and the site login window;
- `jvm_classes` on a project class after a rebuild (the bundle lookup was checked on an AEM class);
- JDWP port detection on Linux and macOS (the live path ran on Windows only);
- AEM 6.5 (built for it; verified so far on the SDK);
- the `aem-debugger` agent in Claude Code on a page (the server connects and the agent gets exactly its tools), and
  other MCP clients such as VS Code and IntelliJ IDEA.

[Spike findings](spike-findings.md#how-to-complete-the-spike) has the end-to-end test that covers part of this.

### Keep the comparison current

- Add the AEM Quickstart MCP server and the Dispatcher MCP server to the README's Side by side table and to why.md's
  comparison and "When to use something else" table. Adobe's page on local development with AI tools (last updated
  17 November 2026) documents both. why.md still says Apache Sling's MCP server isn't documented for AEM; the
  Quickstart MCP server now brings the same kind of tools to the SDK.
- Recheck the dated comparisons every few months.

### More server-side views

Read-only and local like the rest, and tied to the page's requests where possible:

- **Query performance:** which queries a page's requests ran, how long they took, and Oak's plan for each
  (`explain`), to find the traversal or missing index behind a slow or empty component. `aem_query` runs a
  QueryBuilder search for you today; this is about the queries the page itself runs. One way to evaluate: raise Oak's
  query logging with `aem_debug_toggle` and match its lines by thread name, as error.log lines are matched today.
- **Thread dump without a debugger:** for a request that hangs, the stack of its request thread (found by its Sling
  thread name) and of the threads it waits on. `jcmd <pid> Thread.print` uses the JDK's attach mechanism, which
  aem-debug-mcp already uses to find the JDWP port, so this would also work on an instance started without JDWP.
- **Sling jobs and workflows on author:** queued, active and failed jobs and workflow instances, for "my workflow
  doesn't run".
- **Replication to a local publish:** agent and queue state, for "my page doesn't show up on publish".

Each needs a check of what the SDK and 6.5 expose, and has to fit the
[safety rules](why.md#safety-why-the-limits-matter-when-an-agent-drives).

### Install and distribution

- **Release the jar.** A plugin installed from a GitHub marketplace gets the repository's files, and the jar isn't
  in Git, so today you build it first. A release workflow could publish the jar, and the plugin's launcher could
  download it on first start, check its SHA-256 and keep it in the plugin's data folder.
- **Listings.** A Copilot plugin marketplace file (`.github/plugin/marketplace.json`), the awesome-copilot list and
  the Antigravity Marketplace, which asks for a display name, a logo, suggested prompts and a README in the plugin.
- **A Claude Code plugin.** Claude Code plugins can hold skills, agents and MCP servers, so today's manual setup
  (`claude mcp add`, then copying the skill and the agent) could become one install. Plugins namespace their MCP
  servers, so the agent's tool list would need the new tool names.

### Routing in AI assistants

Assistants pick skills and agents from their descriptions; there is no keyword mapping.

- In Copilot CLI on 25 November 2026, three prompts about a broken WKND page on a local author went to the
  `aem-local-debugging` skill: two were handed to the `aem-debugger` agent and one called the tools directly. A prompt
  to create a component went to Adobe's `create-component` skill. Repeat the check when the descriptions change.
- In our test, Antigravity's main agent didn't hand a plain request to the `aem-debugger` agent by itself; you had to
  name the agent. Check whether the skill or agent wording can change that.
- Consider a `/aem-debug` entry point in the plugin, and a suggested `AGENTS.md` line in [setup](setup.md), for users
  who want the investigation every time.

## Ideas, not decided

- **Cloud and server-side debugging, one possible shape.** A separate, opt-in server for RDEs, development and stage
  environments, built in steps: first reading Developer Console dumps and Cloud Manager log files you downloaded,
  then logs and renders of allowlisted hosts, then author reads. It would be read-only, except perhaps a
  one-property content fix on development authors that you confirm each time. The local server would keep its
  local-only check, which stops a pasted or injected link from sending the agent, with your session, to another
  host. JVM debugging isn't possible there: cloud environments have no JDWP.
- **A broader name.** aem-debug-mcp also helps check changes during development, not only debug them. Candidates:
  `aem-lens`, `aem-xray`, `aem-devloop`. Already used by other projects: `aem-dev-mcp` (an AEM MCP server) and
  `aem-inspector` (a browser extension).

## Where aem-debug-mcp stands

The README's [Side by side](../README.md#side-by-side) table and [How it compares](why.md#how-it-compares) have the
full comparison and its sources.

### The AEM MCP servers around it

*As of November 2026; these tools change quickly.*

| MCP server | Runs against | What it gives an agent | For a bug on a local page |
|---|---|---|---|
| [Adobe's AEM MCP server](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/ai-in-aem/mcp-support/using-mcp-with-aem-as-a-cloud-service) | AEM as a Cloud Service, as the signed-in user | Pages, content fragments and assets; Cloud Manager programs, environments and pipelines; Experience Governance; Cloud Migration | Its documentation covers cloud environments, not a local SDK. No rendering, request-log or JVM tools |
| [AEM Quickstart MCP server](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/ai-in-aem/local-development-with-ai/local-development-with-ai-tools#aem-quickstart-mcp-server) | A local AEM as a Cloud Service SDK, built in at `/bin/mcp` | `aem-logs`, `diagnose-osgi-bundle` and `recent-requests` | Recent logs and requests, not tied to a page load. No rendering, component mapping or JVM state |
| [Dispatcher MCP server](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/ai-in-aem/local-development-with-ai/local-development-with-ai-tools#dispatcher-mcp-server) | A local Dispatcher SDK in Docker | Seven tools: configuration validation and lint, SDK checks, request tracing, cache inspection, metrics and logs | Dispatcher only |
| [Apache Sling MCP server](https://sling.apache.org/documentation/bundles/mcp-server.html) | A Sling instance at `/bin/mcp`, documented with the Sling Starter | `logs`, `recent-requests` and `diagnose-osgi-bundle` | Recent logs and requests, not tied to a page load |
| Community AEM MCP servers ([details](why.md#the-tools-in-more-detail)) | A local AEM or AEM as a Cloud Service, with credentials | Content and administration, some of it with changes | Their documentation doesn't cover rendering or the JVM |

The AEM Quickstart MCP server uses the same `/bin/mcp` path and similar tools as Apache Sling's MCP server. Agents
also use general-purpose MCP servers for AEM work: browser tools such as Playwright MCP and Chrome DevTools MCP for the
page, and IntelliJ IDEA's debugger tools or mcp-jdwp-java for the JVM.

### What aem-debug-mcp adds

For a bug on a local AEM, the agent collects four views itself and joins them per HTTP request: the rendered page,
the components and content behind it, the logs of that request, and the JVM state. You no longer paste screenshots,
log lines and variable values into the chat. It stays local and read-only, keeps passwords out of the chat, and never
pauses AEM.

Where it stands out:

- **From page to source.** `debug_page` renders the page in headless Edge or Chrome and numbers each component (#n)
  on the screenshot. Each one comes with its resource path and type, whether it rendered empty, its Sling Model and
  HTL script, and the matching local files. `component_inspect` then compares the stored content with what the model
  exposes. We haven't found another tool that gives an agent this map. For a person, AEM 6.5's Developer mode in the
  page editor and browser extensions such as aem-inspector show the components on a page, and content servers list
  a page's components from the repository.
- **One page load, joined.** Sling names each request thread after its request, and the same name appears in
  error.log and in the JVM. So `debug_page` matches error.log lines, exceptions, snapshots and Request Progress
  Tracker entries to the browser requests of that page load, and sets aside other tabs, the author UI and background
  jobs. The AEM Quickstart MCP server returns recent logs and requests, which you would still match to the page
  yourself.
- **AEM never pauses.** Snapshot breakpoints record the stack, locals and `this`, then let the thread run on; they
  take conditions and request filters. `debug_page` arms its exception watch on project code, Sling Models and HTL
  only while the page renders, and catches exceptions where they are thrown, including swallowed ones. The agent
  triggers the request itself, so there is no race between pausing and reproducing. IntelliJ IDEA's agent logpoints
  don't pause either, but its documented tools don't tie a hit to a page or a request, and its breakpoints pause the
  request.
- **Dev servers in front of AEM.** For a React app on `localhost:3000` that proxies to AEM, the browser side comes
  from the dev server and the server side from the AEM behind it, matched by method and path.
- **"Is my latest build running?"** `jvm_classes` compares the class running in AEM (bundle, revision, line table)
  with your local build, and `aem_status` checks that your project's bundles are installed.
- **Signing in.** You sign in in a browser window, as local admin, through SSO or with a site's own login, and only
  the session token is kept. No tool takes a password. The AEM Quickstart MCP server's documented client
  configuration sends Basic credentials in a header: `admin:admin` on a default SDK.

Verified on a local AEM SDK: the component map with links to local source files, error.log lines matched to the
page's own requests, the temporary exception watch during a render, dev servers in front of publish, and the login
window. Request Progress Tracker matching and snapshots in project code are implemented and tested, but not yet
verified on a real page (see [Status and limits](../README.md#status-and-limits)).

### Where other tools lead

Use them alongside aem-debug-mcp:

- **AEM Quickstart MCP server:** nothing to install, since it ships with the SDK. Its three tools overlap three of
  aem-debug-mcp's 24: `aem_logs`, `aem_osgi_status` and `aem_recent_requests`. It's enough for a quick look at the
  logs or a bundle; aem-debug-mcp adds the page, the components, the JVM and the join.
- **Adobe's AEM MCP server:** creating and changing content and assets, Cloud Manager pipelines, and cloud
  environments. aem-debug-mcp only reads, and only locally.
- **The AEM Developer Console:** read-only views of cloud environments, including Sling jobs, Oak index definitions
  and a query performance tool
  ([docs](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/implementing/developing/aem-developer-console)).
  aem-debug-mcp has no tools yet for query performance (which queries ran, and their plans), Sling jobs, workflows,
  replication or thread dumps (see [More server-side views](#more-server-side-views)).
- **Dispatcher MCP server:** Dispatcher configuration, caching and rewrites.
- **IntelliJ IDEA:** stepping through code, changing values while paused, and thread dumps. AEM accepts one debugger
  at a time, so aem-debug-mcp detaches first.
