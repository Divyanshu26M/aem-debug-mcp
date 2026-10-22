package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemResponse;
import dev.aemdebug.aem.BundleInfo;
import dev.aemdebug.aem.FelixConsole;
import dev.aemdebug.aem.SlingModelRegistry;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** aem_osgi_status: bundle, component, installer and Sling Models health. */
public final class AemOsgiStatusTool extends AbstractTool {

    private static final Pattern INSTALLER_PROBLEM =
            Pattern.compile("(?i)(fail|error|exception|untransformed|not installed|\\bINSTALL\\b|\\bUNINSTALL\\b)");

    private final Services s;

    public AemOsgiStatusTool(Services services) {
        super("aem_osgi_status", "OSGi runtime status",
                "Reports OSGi runtime problems on the local AEM: bundles that are not active (e.g. unresolved imports "
                        + "after a deploy), unsatisfied/failed DS components, OSGi installer problems, and Sling Models "
                        + "registrations (which model class is bound to which resource type). Use 'filter' to focus on "
                        + "your project (e.g. com.mysite). Read-only.",
                Schema.object()
                        .str("filter", "Only show bundles/components/models whose name contains this text")
                        .strArray("include", "Sections: bundles, components, installer, models (default all)")
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        String filter = args.str("filter");
        List<String> include = args.strList("include");
        StringBuilder out = new StringBuilder("### OSGi status (").append(instance.name()).append(")\n");
        if (include.isEmpty() || include.contains("bundles")) {
            section(out, "Bundles", () -> bundles(context, instance, filter));
        }
        if (include.isEmpty() || include.contains("components")) {
            section(out, "Components", () -> components(context, instance, filter));
        }
        if (include.isEmpty() || include.contains("installer")) {
            section(out, "OSGi installer", () -> installer(context, instance, filter));
        }
        if (include.isEmpty() || include.contains("models")) {
            section(out, "Sling Models", () -> models(context, instance, filter));
        }
        return ToolResult.of(ToolOutput.inlineOrArtifact(s, out.toString(), ToolOutput.INLINE_LIMIT, "osgi-status.md"));
    }

    private interface Section {
        String render();
    }

    private static void section(StringBuilder out, String title, Section section) {
        out.append("\n#### ").append(title).append('\n');
        try {
            out.append(section.render());
        } catch (RuntimeException e) {
            out.append("unavailable: ").append(Text.oneLine(e.getMessage(), 300)).append('\n');
        }
    }

    private String bundles(ToolContext context, AemInstance instance, String filter) {
        List<BundleInfo> bundles = s.felix.bundles(context, instance);
        List<BundleInfo> problems = new ArrayList<>();
        List<BundleInfo> matching = new ArrayList<>();
        for (BundleInfo bundle : bundles) {
            boolean matches = filter == null || contains(bundle.symbolicName, filter) || contains(bundle.name, filter);
            if (!bundle.healthy()) {
                problems.add(bundle);
            }
            if (filter != null && matches) {
                matching.add(bundle);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(bundles.size()).append(" bundles, ").append(problems.size()).append(" not active\n");
        for (BundleInfo bundle : problems) {
            sb.append("- NOT ACTIVE ").append(bundle).append('\n');
        }
        if (filter != null) {
            sb.append("Bundles matching '").append(filter).append("':\n");
            int detailed = 0;
            for (BundleInfo bundle : matching) {
                sb.append("- ").append(bundle).append(detailed++ < 10 ? detail(context, instance, bundle) : "")
                        .append('\n');
            }
        }
        if (!problems.isEmpty()) {
            sb.append("Tip: open /system/console/bundles/<id> to see unresolved imports, or check aem_logs "
                    + "for 'Unable to resolve'.\n");
        }
        return sb.toString();
    }

    private String detail(ToolContext context, AemInstance instance, BundleInfo bundle) {
        try {
            Map<String, String> detail = s.felix.bundleDetail(context, instance, bundle.id);
            String modified = detail.get("Last Modification");
            StringBuilder sb = new StringBuilder();
            if (modified != null) {
                sb.append(" - last modified ").append(modified);
            }
            String imports = detail.get("Imported Packages");
            if (!bundle.healthy() && imports != null && imports.toLowerCase(Locale.ROOT).contains("error")) {
                sb.append(" - unresolved imports: ").append(Text.oneLine(imports, 400));
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String components(ToolContext context, AemInstance instance, String filter) {
        List<FelixConsole.ComponentState> components = s.felix.components(context, instance);
        StringBuilder sb = new StringBuilder();
        int problems = 0;
        for (FelixConsole.ComponentState component : components) {
            boolean matches = filter == null || contains(component.name, filter);
            if (component.problematic() && matches) {
                problems++;
                if (problems <= 40) {
                    sb.append("- ").append(component.name).append(": ").append(component.state).append('\n');
                }
            }
        }
        return components.size() + " components, " + problems + " unsatisfied/failed"
                + (filter == null ? "" : " matching '" + filter + "'") + "\n" + sb;
    }

    private String installer(ToolContext context, AemInstance instance, String filter) {
        AemResponse response = s.aem.get(context, instance, "/system/console/status-osgi-installer.txt");
        if (!response.ok()) {
            return "installer status printer not available (HTTP " + response.status() + ")\n";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : response.text().split("\\R")) {
            if (INSTALLER_PROBLEM.matcher(line).find() && (filter == null || contains(line, filter))) {
                if (++count <= 25) {
                    sb.append("- ").append(Text.oneLine(line, 240)).append('\n');
                }
            }
        }
        return count == 0 ? "no installer problems reported\n" : count + " installer lines need attention:\n" + sb;
    }

    private String models(ToolContext context, AemInstance instance, String filter) {
        SlingModelRegistry registry = s.felix.slingModels(context, instance);
        StringBuilder sb = new StringBuilder();
        sb.append(registry.bindingCount()).append(" resource type bindings, ").append(registry.injectorCount)
                .append(" injectors\n");
        if (filter != null) {
            int shown = 0;
            for (Map<String, List<String>> map : List.of(registry.forRequests, registry.forResources)) {
                for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                    for (String model : entry.getValue()) {
                        if ((contains(model, filter) || contains(entry.getKey(), filter)) && shown++ < 60) {
                            sb.append("- ").append(entry.getKey()).append(" -> ").append(model)
                                    .append(map == registry.forRequests ? " (request)" : " (resource)").append('\n');
                        }
                    }
                }
            }
            if (shown == 0) {
                sb.append("No model bound to a resource type matching '").append(filter).append("'. If your model "
                        + "should be registered, check that its bundle is active and the package is listed in "
                        + "Sling-Model-Packages / the bnd model scanner.\n");
            }
        }
        return sb.toString();
    }

    private static boolean contains(String value, String filter) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}
