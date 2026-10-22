# aem-debug-mcp

A local MCP server (shipped as a GitHub Copilot plugin) that lets an AI agent debug AEM local development
end-to-end: render pages on the local AEM SDK, map the rendered output to AEM components and source code,
read content and config, and capture JVM runtime state over JDWP — all correlated to the HTTP request that
produced the page.

> Status: under active development.

## Planned capabilities

- Page rendering through a headless browser: screenshots, DOM, console errors, failed network requests.
- Component mapping: DOM element → resource path → resourceType → script → Sling Model → local source file.
- Read-only content and config access, authorized by the user through the AEM login page.
- Snapshot-first JVM debugging over JDWP: breakpoint snapshots and exceptions captured at the throw site.
- Request-level correlation of browser requests, JVM events, `error.log` lines and Sling Request Progress
  Tracker data.

## Requirements

- JDK 17 or later (JDK 21 recommended).
- A local AEM instance started with JDWP enabled, for example:

  ```
  java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8767 -jar aem-sdk-author-p4502.jar -gui
  ```
