package dev.aemdebug.tools.impl;

import dev.aemdebug.server.Services;
import dev.aemdebug.tools.ToolRegistry;

/** Registers every MCP tool of the server. */
public final class ToolFactory {

    private ToolFactory() {
    }

    public static ToolRegistry createRegistry(Services services) {
        ToolRegistry registry = new ToolRegistry(services.activity);
        registry.register(new AemStatusTool(services));
        registry.register(new AemAuthTool(services));
        registry.register(new AemGetResourceTool(services));
        registry.register(new AemQueryTool(services));
        registry.register(new AemGetModelJsonTool(services));
        registry.register(new AemPageContextTool(services));
        registry.register(new AemGetOsgiConfigTool(services));
        registry.register(new AemOsgiStatusTool(services));
        registry.register(new AemResolveTool(services));
        registry.register(new AemLogsTool(services));
        registry.register(new AemRecentRequestsTool(services));
        registry.register(new AemDebugToggleTool(services));
        registry.register(new PageRenderTool(services));
        registry.register(new PageComponentMapTool(services));
        registry.register(new ComponentInspectTool(services));
        registry.register(new ProjectIndexTool(services));
        registry.register(new JvmConnectionTool(services));
        registry.register(new JvmBreakpointTool(services));
        registry.register(new JvmExceptionWatchTool(services));
        registry.register(new JvmSnapshotsTool(services));
        registry.register(new JvmClassesTool(services));
        registry.register(new DebugPageTool(services));
        DebugCaptureTool capture = new DebugCaptureTool(services);
        registry.register(capture);
        registry.register(new DebugCleanupTool(services, capture));
        return registry;
    }
}
