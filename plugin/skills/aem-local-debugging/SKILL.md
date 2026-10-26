---
name: aem-local-debugging
description: Debug a local AEM instance (AEM as a Cloud Service SDK or 6.5 on localhost) end to end with the aem-debug MCP server - render pages, map the rendered output to components and source files, read content and OSGi config, capture JVM snapshots and exceptions over JDWP, and correlate everything to the HTTP request. Use when the user reports an empty/broken/wrong component, a page error (500/404), missing styles or scripts (clientlib 404), code changes that do not show up, or policy/style-system problems on localhost:4502/4503.
---

# Local AEM debugging with aem-debug-mcp

You can see what the local AEM renders, which component produced it, what content and configuration it used,
and what happened inside the JVM for that request. Investigate yourself instead of asking the user for
screenshots, logs or variable values.

In Copilot, this plugin also provides the diagnose-only `aem-debug-mcp:aem-debugger` agent. To keep
screenshots, logs and snapshots out of the main conversation, hand it the investigation. It returns the cause,
the evidence and the file and line to fix, and you then make the fix here.

## Ground rules

- Only local instances are reachable (localhost, 127.0.0.1, ::1). Everything is read-only except
  `aem_debug_toggle`, which changes debug settings only and is reverted automatically.
- **Login**: when AEM needs authentication a browser window opens on the AEM login page. Tell the user to sign
  in there (local admin or SSO) and retry the tool. Never ask for passwords in chat and never put credentials
  in tool arguments.
- **JDWP is exclusive**: AEM accepts one debugger. Before `jvm_connection` / `jvm_breakpoint` /
  `jvm_exception_watch` / `debug_page` with JVM capture, make sure IntelliJ (or another debugger) is detached -
  `aem_status` tells you if the port is busy. When done, `jvm_connection action=detach` (or `debug_cleanup`)
  hands the port back. The server auto-detaches after inactivity.
- Breakpoints never pause AEM: they take a snapshot (stack, locals, `this`) and resume immediately.
- **Never call `aem_debug_toggle` with `confirm=true` unless the user explicitly approved that exact change**
  in this conversation. Prefer `action=plan` first and show the user what would change.
- Finish with `debug_cleanup` (removes breakpoints and watches, reverts toggles, detaches JDWP, closes browsers).

## The loop

1. `project_index workspace=<your current working folder>` (absolute path) - links AEM to the local source.
   The server cannot see which folder the chat runs in, so always pass it. If the result says no AEM project
   is known, ask the user where the AEM project's source is (one repository, an aggregator repository, or
   several repositories deployed into the same AEM) and call `project_index projectDir=<folder>` for each. The
   answer is remembered for this AEM, so later chats do not ask again. Without a project everything still
   works, but nothing links to source files.
2. `aem_status` - reachability, login state, AEM version, run modes, log location, bundle health, JDWP port, and
   whether the local project's bundles are installed (a "NOT deployed here" line means the wrong folder or an
   undeployed project: tell the user).
3. Reproduce with `debug_page path=/content/...` - one call renders the page (screenshot, console errors,
   failed requests), maps components, watches exceptions in project code / Sling Models / HTL on the server,
   collects error.log lines and the Request Progress Tracker for that request, and returns a Debug Report with
   hints. Add `breakpoints` (e.g. `["com.mysite.core.models.HeroModel:41"]`) to capture variable values.
4. Identify the component: the report and `page_component_map` number components (#n) on the screenshot.
5. Inspect: `component_inspect path=<component path>` (JCR vs model JSON vs model fields, HTL, policy,
   HTML fragment, related logs). Use `aem_get_resource`, `aem_get_model_json`, `aem_page_context`,
   `aem_get_osgi_config`, `aem_resolve`, `aem_logs`, `aem_recent_requests` for specifics.
6. Look inside the JVM: `jvm_breakpoint action=set location=Class:line` (or `File.java:line`, or
   `Class#method`), optional `condition` like `title == null`, then re-run `debug_page`; read values with
   `jvm_snapshots`. Use `jvm_exception_watch` for exceptions that are caught and swallowed.
7. Diagnose in the local source (`project_index` maps classes, models, HTL, configs to files), fix the code.
8. Ask the user to rebuild/deploy (e.g. `mvn clean install -PautoInstallSinglePackage`), then check
   `jvm_classes` (stale class?) and verify with `debug_page`.
9. `debug_cleanup`.

Use `debug_capture action=start` when the user wants to reproduce manually in their own browser or in the
AEM author UI, then `debug_capture action=stop` for the per-request report.

## Recipes

**Component renders empty / shows no title**
`debug_page` -> find the component (#n, "rendered empty") -> `component_inspect`. Typical causes: model field
injects a different property name than the content stores (`title` vs `jcr:title` -> `@ValueMapValue(name =
"jcr:title")`), required injection missing (MissingElementsException in the report/logs; make it optional or
fix content), HTL reads the wrong getter, or the component's model is not registered (`aem_osgi_status
filter=<package>` -> Sling Models section). Confirm with a snapshot breakpoint in the getter or `@PostConstruct`.

**Page returns 500 / error page**
`debug_page` shows server-side exceptions at the throw site for that request (with locals) and the matching
error.log entries. If nothing was captured, add `jvm_exception_watch` with the right package and re-render.
`aem_resolve url=...` explains which script/servlet handles the URL.

**Wrong data rendered**
Compare `aem_get_resource` (JCR) with `aem_get_model_json`; set a breakpoint where the value is computed and
look at `this`/locals in `jvm_snapshots`. Check `aem_page_context` for language/CA-config/policy differences.

**Styles or JavaScript missing (clientlib 404)**
`page_render` lists failed requests. A 404 on `/etc.clientlibs/...` usually means the client library folder
lacks `allowProxy=true` or the category name is wrong: check `project_index show=clientlibs`.
Console errors are listed with their source location.

**Code change not reflected**
`jvm_classes class=<FQN>` shows loaded versions per bundle and compares the deployed line table with the local
build (stale bundle, old class version still wired, or local build not deployed). `aem_osgi_status` shows
bundles that failed to resolve after the deploy.

**Policy / style system problems**
`aem_page_context path=<page> component=<component path>` shows the template, the policy mapped to the
component and its properties.

**Need more logging or request detail**
Plan first, ask the user, then enable: `aem_debug_toggle toggle=log-level loggers=["com.mysite"]`,
`toggle=rpt-log`, `toggle=recent-requests`, or `toggle=wcm-debug-filter`. They are reverted by
`debug_cleanup`, after inactivity, or when the server stops.

## Reading the Debug Report

- **Requests**: document status/time, failed assets, XHRs.
- **Components**: #n matches the numbered boxes; `EMPTY` means no text/media; model and source file are local
  paths you can open.
- **Server-side events on this request**: exceptions (caught/uncaught) with throw location and locals, and
  breakpoint snapshots - matched to the request via the Sling thread name.
- **Log lines on this request**: error.log entries written by the same request thread.
- **Hints**: concrete mismatches (e.g. "JCR has jcr:title but field title injects title").
- **Artifacts**: full HTML, full-page screenshot, logs, RPT, snapshots - `aemdbg://reports/...` resources and
  local file paths you can read directly.
