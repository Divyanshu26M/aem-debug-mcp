package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.OsgiConfiguration;
import dev.aemdebug.aem.ResourceTree;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** aem_get_osgi_config: effective OSGi configuration and where it is defined. */
public final class AemGetOsgiConfigTool extends AbstractTool {

    private final Services s;

    public AemGetOsgiConfigTool(Services services) {
        super("aem_get_osgi_config", "Get OSGi configuration",
                "Shows the effective OSGi configuration of a PID (or all PIDs matching a pattern) on the local AEM, "
                        + "plus where it is defined: config nodes in the repository (/apps/*/osgiconfig/config.<runmode>) "
                        + "and matching files in the local project (ui.config). Secret properties are masked. Read-only.",
                Schema.object()
                        .str("pid", "Exact PID or factory PID, e.g. org.apache.sling.commons.log.LogManager.factory.config")
                        .str("pattern", "Substring or regex matched against all configured PIDs")
                        .bool("includeRepository", "Also search /apps and /libs for config nodes (default true)")
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        String pid = args.str("pid");
        String pattern = args.str("pattern");
        if (pid == null && pattern == null) {
            throw new ToolException("Give either pid or pattern");
        }
        boolean includeRepository = args.bool("includeRepository", true);
        StringBuilder out = new StringBuilder();
        List<String> pidsForDefinitions = new ArrayList<>();
        if (pid != null) {
            out.append("### OSGi configuration ").append(pid).append(" (").append(instance.name()).append(")\n");
            OsgiConfiguration config = s.felix.config(context, instance, pid);
            if (config != null) {
                appendConfig(out, config);
            } else {
                List<OsgiConfiguration> factory = s.felix.factoryConfigurations(context, instance, pid);
                if (factory.isEmpty()) {
                    out.append("No explicit configuration exists for this PID: the component uses its defaults "
                            + "(metatype/annotations), or the PID is misspelled.\n");
                } else {
                    out.append("Factory configurations (").append(factory.size()).append("):\n");
                    for (OsgiConfiguration item : factory) {
                        appendConfig(out, item);
                    }
                }
            }
            pidsForDefinitions.add(pid);
        } else {
            Pattern regex = compile(pattern);
            List<OsgiConfiguration> matches = new ArrayList<>();
            for (OsgiConfiguration config : s.felix.configurations(context, instance)) {
                if (config.pid != null && (regex.matcher(config.pid).find()
                        || (config.factoryPid != null && regex.matcher(config.factoryPid).find()))) {
                    matches.add(config);
                }
            }
            out.append("### OSGi configurations matching '").append(pattern).append("' (").append(matches.size())
                    .append(")\n");
            for (int i = 0; i < matches.size() && i < 40; i++) {
                OsgiConfiguration config = matches.get(i);
                if (matches.size() <= 5) {
                    appendConfig(out, config);
                    pidsForDefinitions.add(config.factoryPid != null ? config.factoryPid : config.pid);
                } else {
                    out.append("- ").append(config.pid).append(" (").append(config.properties.size())
                            .append(" properties)\n");
                }
            }
            if (matches.size() > 40) {
                out.append("... ").append(matches.size() - 40).append(" more\n");
            }
        }
        if (includeRepository) {
            for (String definitionPid : pidsForDefinitions) {
                appendRepositoryDefinitions(context, instance, definitionPid, out);
            }
        }
        for (String definitionPid : pidsForDefinitions) {
            String local = ProjectHooks.osgiConfigFiles(s, definitionPid);
            if (local != null) {
                out.append(local);
            }
        }
        return ToolResult.of(ToolOutput.inlineOrArtifact(s, out.toString(), ToolOutput.INLINE_LIMIT, "osgi-config.md"));
    }

    private static Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
        }
    }

    private static void appendConfig(StringBuilder out, OsgiConfiguration config) {
        out.append("- PID ").append(config.pid);
        if (config.factoryPid != null) {
            out.append(" (factory ").append(config.factoryPid).append(')');
        }
        if (config.bundleLocation != null) {
            out.append(" bound to ").append(config.bundleLocation);
        }
        out.append(" [").append(config.source).append("]\n");
        Map<String, Object> masked = Json.asMap(SecretMasker.maskTree(new LinkedHashMap<>(config.properties)));
        for (Map.Entry<String, Object> entry : masked.entrySet()) {
            out.append("    ").append(entry.getKey()).append(" = ").append(ResourceTree.formatValue(entry.getValue()))
                    .append('\n');
        }
    }

    private void appendRepositoryDefinitions(ToolContext context, AemInstance instance, String pid, StringBuilder out) {
        List<String> found = new ArrayList<>();
        for (String root : new String[] {"/apps", "/libs"}) {
            try {
                Map<String, String> predicates = new LinkedHashMap<>();
                predicates.put("path", root);
                predicates.put("nodename", pid + "*");
                predicates.put("p.hits", "selective");
                predicates.put("p.properties", "jcr:path jcr:primaryType");
                Map<String, Object> result = s.content.query(context, instance, predicates, 30);
                for (Object hit : Json.asList(result.get("hits"))) {
                    String path = Json.asString(Json.asMap(hit).get("jcr:path"));
                    if (path != null && !path.contains("/jcr:content")) {
                        found.add(path);
                    }
                }
            } catch (ToolException e) {
                out.append("  (repository search in ").append(root).append(" failed: ").append(e.getMessage()).append(")\n");
            }
        }
        if (found.isEmpty()) {
            out.append("Repository: no config node named ").append(pid).append("* under /apps or /libs (it may come "
                    + "from the Web Console, a bundle default, or a factory instance with a generated name).\n");
            return;
        }
        out.append("Repository definitions of ").append(pid).append(":\n");
        for (String path : found) {
            out.append("- ").append(path).append("  (run modes: ").append(runModesOf(path)).append(")\n");
        }
    }

    /** "config.author.dev" -> "author.dev"; "config" -> "all". */
    static String runModesOf(String path) {
        String[] segments = path.split("/");
        for (int i = segments.length - 2; i >= 0; i--) {
            String segment = segments[i];
            String lower = segment.toLowerCase(Locale.ROOT);
            if (lower.equals("config") || lower.equals("osgiconfig")) {
                return "all";
            }
            if (lower.startsWith("config.")) {
                return segment.substring("config.".length());
            }
        }
        return "?";
    }

    static String summarize(String text) {
        return Text.oneLine(text, 200);
    }
}
