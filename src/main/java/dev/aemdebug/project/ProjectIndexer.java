package dev.aemdebug.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a local AEM project (Maven multi-module, FileVault content packages) with lightweight source parsing:
 * Sling Models and their injected fields, components and HTL scripts, servlets, OSGi config files, client libraries.
 */
public final class ProjectIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectIndexer.class);

    private static final Set<String> SKIP_DIRS = Set.of("node_modules", "target", ".git", ".idea", ".vscode", "dist",
            ".cache", "coverage", ".angular", ".gradle", ".svn", ".mvn");
    private static final int MAX_FILES = 80_000;
    private static final long MAX_FILE_SIZE = 3_000_000;

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE_DECL = Pattern.compile("(?<![.\\w])(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern CLASS_LITERAL = Pattern.compile("([\\w.]+)\\.class");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FIELD_DECL = Pattern.compile(
            "^\\s*(?:(?:private|protected|public|static|final|transient|volatile)\\s+)*([\\w.$\\[\\]]+(?:\\s*<[^;=()]*>)?(?:\\[\\])*)\\s+(\\w+)\\s*(?:=[^;]*)?;\\s*$");
    private static final Pattern METHOD_DECL = Pattern.compile("\\b(\\w+)\\s*\\([^)]*\\)\\s*(?:throws[^{]*)?\\{?\\s*$");
    private static final Pattern INJECTION = Pattern.compile(
            "@(ValueMapValue|Inject|ChildResource|ResourcePath|OSGiService|ScriptVariable|SlingObject|RequestAttribute|Self|ChildResourceFromRequest)\\b");
    private static final Pattern NAME_ATTR = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern XML_ATTR = Pattern.compile("([\\w:.\\-]+)=\"([^\"]*)\"");
    private static final Pattern HTL_USE = Pattern.compile("data-sly-use\\.([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']+)'");

    private ProjectIndexer() {
    }

    public static ProjectIndex index(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<ProjectModel.Module> modules = new ArrayList<>();
        List<ProjectModel.JavaClass> classes = new ArrayList<>();
        List<ProjectModel.SlingModel> models = new ArrayList<>();
        List<ProjectModel.Component> components = new ArrayList<>();
        List<ProjectModel.HtlScript> scripts = new ArrayList<>();
        List<ProjectModel.Servlet> servlets = new ArrayList<>();
        List<ProjectModel.OsgiConfigFile> configs = new ArrayList<>();
        List<ProjectModel.ClientLib> clientLibs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int[] files = {0};

        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(base) && SKIP_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (++files[0] > MAX_FILES) {
                    warnings.add("stopped after " + MAX_FILES + " files");
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.size() > MAX_FILE_SIZE) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    visit(file, modules, classes, models, components, scripts, servlets, configs, clientLibs, warnings);
                } catch (IOException | RuntimeException e) {
                    LOG.debug("Cannot index {}: {}", file, e.toString());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        for (ProjectModel.HtlScript script : scripts) {
            for (ProjectModel.Component component : components) {
                if (component.resourceType.equals(script.resourceType)) {
                    component.scripts.add(script.file);
                }
            }
        }
        for (ProjectModel.Module module : modules) {
            Path bnd = module.dir.resolve("bnd.bnd");
            if (Files.isRegularFile(bnd)) {
                Matcher m = Pattern.compile("(?m)^Bundle-SymbolicName:\\s*(\\S+)").matcher(read(bnd));
                if (m.find()) {
                    module.bundleSymbolicName = m.group(1);
                }
            }
        }
        warnMissingUseClasses(classes, scripts, base, warnings);
        return new ProjectIndex(base, modules, classes, models, components, scripts, servlets, configs, clientLibs,
                warnings);
    }

    private static void visit(Path file, List<ProjectModel.Module> modules, List<ProjectModel.JavaClass> classes,
                              List<ProjectModel.SlingModel> models, List<ProjectModel.Component> components,
                              List<ProjectModel.HtlScript> scripts, List<ProjectModel.Servlet> servlets,
                              List<ProjectModel.OsgiConfigFile> configs, List<ProjectModel.ClientLib> clientLibs,
                              List<String> warnings) throws IOException {
        String name = file.getFileName().toString();
        String unixPath = file.toString().replace('\\', '/');
        if (name.equals("pom.xml")) {
            modules.add(parsePom(file, read(file)));
        } else if (name.endsWith(".java") && unixPath.contains("/src/main/java/")) {
            parseJava(file, read(file), classes, models, servlets, warnings);
        } else if (unixPath.contains("/jcr_root/")) {
            String jcrPath = jcrPath(file);
            if (name.equals(".content.xml")) {
                parseContentXml(file, jcrPath, read(file), components, clientLibs);
            } else if (name.endsWith(".html") && jcrPath.startsWith("/apps/")) {
                scripts.add(parseHtl(file, jcrPath, read(file)));
            } else if (isOsgiConfig(file, jcrPath, name)) {
                if (!name.endsWith(".xml") || read(file).contains("sling:OsgiConfig")) {
                    configs.add(osgiConfig(file, jcrPath, name));
                }
            }
        }
    }

    private static String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** HTL data-sly-use classes in a project package without a source file are likely typos or missing classes. */
    static void warnMissingUseClasses(List<ProjectModel.JavaClass> classes, List<ProjectModel.HtlScript> scripts,
                                      Path base, List<String> warnings) {
        java.util.Set<String> fqns = new java.util.HashSet<>();
        java.util.Set<String> prefixes = new java.util.HashSet<>();
        for (ProjectModel.JavaClass javaClass : classes) {
            fqns.add(javaClass.fqn);
            String[] parts = javaClass.fqn.split("\\.");
            if (parts.length >= 3) {
                prefixes.add(parts[0] + "." + parts[1] + ".");
            }
        }
        for (ProjectModel.HtlScript script : scripts) {
            for (java.util.Map.Entry<String, String> use : script.useObjects.entrySet()) {
                String value = use.getValue();
                if (!value.matches("[A-Za-z_][\\w$]*(\\.[A-Za-z_][\\w$]*)+") || value.endsWith(".html")
                        || value.endsWith(".js")) {
                    continue;
                }
                boolean projectPackage = false;
                for (String prefix : prefixes) {
                    if (value.startsWith(prefix)) {
                        projectPackage = true;
                        break;
                    }
                }
                if (projectPackage && !fqns.contains(value)) {
                    String file;
                    try {
                        file = base.relativize(script.file).toString().replace('\\', '/');
                    } catch (IllegalArgumentException e) {
                        file = script.file.toString();
                    }
                    warnings.add(file + ": data-sly-use." + use.getKey() + "=\"" + value
                            + "\" - no such class in the project sources (typo, missing class, or wrong package)");
                }
            }
        }
    }

    // ------------------------------------------------------------------ Maven

    static ProjectModel.Module parsePom(Path file, String xml) {
        ProjectModel.Module module = new ProjectModel.Module();
        module.dir = file.getParent();
        module.name = file.getParent().getFileName() == null ? "" : file.getParent().getFileName().toString();
        String withoutParent = xml.replaceAll("(?s)<parent>.*?</parent>", "");
        String withoutDeps = withoutParent.replaceAll("(?s)<dependencies>.*?</dependencies>", "")
                .replaceAll("(?s)<build>.*?</build>", "").replaceAll("(?s)<profiles>.*?</profiles>", "")
                .replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "");
        module.artifactId = firstTag(withoutDeps, "artifactId");
        module.groupId = firstTag(withoutDeps, "groupId");
        if (module.groupId == null) {
            Matcher parent = Pattern.compile("(?s)<parent>(.*?)</parent>").matcher(xml);
            if (parent.find()) {
                module.groupId = firstTag(parent.group(1), "groupId");
            }
        }
        module.packaging = firstTag(withoutDeps, "packaging");
        return module;
    }

    private static String firstTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------ Java

    static void parseJava(Path file, String source, List<ProjectModel.JavaClass> classes,
                          List<ProjectModel.SlingModel> models, List<ProjectModel.Servlet> servlets,
                          List<String> warnings) {
        String text = stripComments(source);
        Matcher pkg = PACKAGE.matcher(text);
        String packageName = pkg.find() ? pkg.group(1) : "";
        Matcher type = TYPE_DECL.matcher(text);
        if (!type.find()) {
            return;
        }
        String simpleName = type.group(2);
        ProjectModel.JavaClass javaClass = new ProjectModel.JavaClass();
        javaClass.simpleName = simpleName;
        javaClass.fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        javaClass.file = file;
        javaClass.lineCount = source.split("\\R", -1).length;
        classes.add(javaClass);

        int modelAt = annotationIndex(text, "Model");
        if (modelAt >= 0) {
            models.add(parseModel(javaClass, file, text, modelAt, warnings));
        }
        if (text.contains("SlingServletResourceTypes") || text.contains("SlingServletPaths")
                || text.contains("sling.servlet.") || text.contains("SLING_SERVLET_")) {
            ProjectModel.Servlet servlet = parseServlet(javaClass, file, text);
            if (servlet != null) {
                servlets.add(servlet);
            }
        }
    }

    /** Index of "@Name(" or "@Name" followed by a non-identifier char, or -1. */
    static int annotationIndex(String text, String name) {
        Matcher m = Pattern.compile("@" + name + "\\s*\\(").matcher(text);
        return m.find() ? m.start() : -1;
    }

    static ProjectModel.SlingModel parseModel(ProjectModel.JavaClass javaClass, Path file, String text, int at,
                                              List<String> warnings) {
        ProjectModel.SlingModel model = new ProjectModel.SlingModel();
        model.className = javaClass.fqn;
        model.file = file;
        model.modelAnnotationLine = lineOf(text, at);
        String body = balancedArgs(text, text.indexOf('(', at));
        model.adaptables.addAll(classLiterals(attribute(body, "adaptables")));
        model.adapters.addAll(classLiterals(attribute(body, "adapters")));
        String resourceType = attribute(body, "resourceType");
        if (resourceType != null) {
            for (String value : stringsOrConstants(resourceType, text)) {
                if (value.startsWith("<unresolved")) {
                    warnings.add(javaClass.fqn + ": resourceType constant " + value + " could not be resolved");
                } else {
                    model.resourceTypes.add(value);
                }
            }
        }
        model.optionalByDefault = body.contains("DefaultInjectionStrategy.OPTIONAL");
        model.exporter = text.contains("@Exporter");
        parseFields(text, model);
        return model;
    }

    private static void parseFields(String text, ProjectModel.SlingModel model) {
        String[] lines = text.split("\\R", -1);
        StringBuilder pending = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (depth > 0) {
                pending.append(' ').append(trimmed);
                depth += parenDelta(trimmed);
                continue;
            }
            if (trimmed.startsWith("@")) {
                pending.append(' ').append(trimmed);
                depth = Math.max(0, parenDelta(trimmed));
                String afterAnnotations = trimmed.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
                if (afterAnnotations.isEmpty() || depth > 0) {
                    continue;
                }
                trimmed = afterAnnotations;
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            String annotations = pending.toString();
            pending.setLength(0);
            if (annotations.isEmpty()) {
                continue;
            }
            Matcher injection = INJECTION.matcher(annotations);
            Matcher field = FIELD_DECL.matcher(trimmed);
            if (injection.find() && field.matches()) {
                ProjectModel.InjectedField f = new ProjectModel.InjectedField();
                f.annotation = injection.group(1);
                f.type = field.group(1).trim();
                f.name = field.group(2);
                Matcher name = NAME_ATTR.matcher(annotations);
                if (name.find()) {
                    f.nameOverride = name.group(1);
                }
                f.optional = model.optionalByDefault || annotations.contains("InjectionStrategy.OPTIONAL")
                        || annotations.contains("@Optional");
                f.hasDefault = annotations.contains("@Default");
                f.line = i + 1;
                model.fields.add(f);
            } else if (annotations.contains("@PostConstruct")) {
                Matcher method = METHOD_DECL.matcher(trimmed);
                if (method.find()) {
                    model.postConstructMethod = method.group(1);
                    model.postConstructLine = i + 1;
                }
            }
        }
    }

    private static int parenDelta(String text) {
        int delta = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString && c == '(') {
                delta++;
            } else if (!inString && c == ')') {
                delta--;
            }
        }
        return delta;
    }

    static ProjectModel.Servlet parseServlet(ProjectModel.JavaClass javaClass, Path file, String text) {
        ProjectModel.Servlet servlet = new ProjectModel.Servlet();
        servlet.className = javaClass.fqn;
        servlet.file = file;
        int resourceTypesAt = annotationIndex(text, "SlingServletResourceTypes");
        if (resourceTypesAt >= 0) {
            String body = balancedArgs(text, text.indexOf('(', resourceTypesAt));
            servlet.resourceTypes.addAll(stringsOrConstants(attribute(body, "resourceTypes"), text));
            servlet.selectors.addAll(stringsOrConstants(attribute(body, "selectors"), text));
            servlet.extensions.addAll(stringsOrConstants(attribute(body, "extensions"), text));
            servlet.methods.addAll(stringsOrConstants(attribute(body, "methods"), text));
        }
        int pathsAt = annotationIndex(text, "SlingServletPaths");
        if (pathsAt >= 0) {
            String body = balancedArgs(text, text.indexOf('(', pathsAt));
            String value = attribute(body, "value");
            servlet.paths.addAll(stringsOrConstants(value == null ? body : value, text));
        }
        addProperty(text, "resourceTypes", "RESOURCE_TYPES", servlet.resourceTypes);
        addProperty(text, "paths", "PATHS", servlet.paths);
        addProperty(text, "selectors", "SELECTORS", servlet.selectors);
        addProperty(text, "extensions", "EXTENSIONS", servlet.extensions);
        addProperty(text, "methods", "METHODS", servlet.methods);
        servlet.resourceTypes.removeIf(v -> v.startsWith("<unresolved"));
        servlet.paths.removeIf(v -> v.startsWith("<unresolved"));
        if (servlet.resourceTypes.isEmpty() && servlet.paths.isEmpty()) {
            return null;
        }
        return servlet;
    }

    private static void addProperty(String text, String name, String constant, List<String> target) {
        Matcher literal = Pattern.compile("\"sling\\.servlet\\." + name + "\\s*=\\s*([^\"]+)\"").matcher(text);
        while (literal.find()) {
            target.add(literal.group(1).trim());
        }
        Matcher concatenated = Pattern.compile("SLING_SERVLET_" + constant + "\\s*\\+\\s*\"=([^\"]+)\"").matcher(text);
        while (concatenated.find()) {
            target.add(concatenated.group(1).trim());
        }
    }

    /** Raw value of name = ... inside annotation arguments (a {...} array, a string, or an expression). */
    static String attribute(String body, String name) {
        if (body == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*").matcher(body);
        if (!m.find()) {
            return null;
        }
        int start = m.end();
        if (start < body.length() && body.charAt(start) == '{') {
            int end = body.indexOf('}', start);
            return end > start ? body.substring(start, end + 1) : body.substring(start);
        }
        int depth = 0;
        boolean inString = false;
        int i = start;
        for (; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' && body.charAt(i - 1) != '\\') {
                inString = !inString;
            } else if (!inString && (c == '(' || c == '{')) {
                depth++;
            } else if (!inString && (c == ')' || c == '}')) {
                depth--;
            } else if (!inString && depth == 0 && c == ',') {
                break;
            }
        }
        return body.substring(start, i).trim();
    }

    static List<String> classLiterals(String value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        Matcher m = CLASS_LITERAL.matcher(value);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    /** String literals in the value; identifiers are resolved against "NAME = \"...\"" constants of the file. */
    static List<String> stringsOrConstants(String value, String fileText) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        Matcher strings = STRING_LITERAL.matcher(value);
        boolean any = false;
        while (strings.find()) {
            result.add(strings.group(1));
            any = true;
        }
        if (!any) {
            for (String part : value.replace("{", "").replace("}", "").split(",")) {
                String identifier = part.trim();
                if (identifier.isEmpty() || !identifier.matches("[\\w.]+")) {
                    continue;
                }
                String simple = identifier.substring(identifier.lastIndexOf('.') + 1);
                Matcher constant = Pattern.compile("\\b" + Pattern.quote(simple) + "\\s*=\\s*\"([^\"]*)\"")
                        .matcher(fileText);
                result.add(constant.find() ? constant.group(1) : "<unresolved " + identifier + ">");
            }
        }
        return result;
    }

    /** Text between the parenthesis at openIndex and its matching close. */
    static String balancedArgs(String text, int openIndex) {
        if (openIndex < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && text.charAt(i - 1) != '\\') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex + 1, i);
                }
            }
        }
        return text.substring(openIndex + 1);
    }

    /** Replaces comment characters with spaces (keeping newlines so line numbers stay valid). */
    static String stripComments(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        boolean inString = false;
        boolean inChar = false;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (!inString && !inChar && c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                for (int j = i; j < end; j++) {
                    sb.append(source.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = end;
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    sb.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '"' && !inChar && (i == 0 || source.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (c == '\'' && !inString && (i == 0 || source.charAt(i - 1) != '\\')) {
                inChar = !inChar;
            } else if (c == '\n') {
                inString = false;
                inChar = false;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ------------------------------------------------------------------ content packages

    /** JCR path of a file below jcr_root, decoding FileVault names (_jcr_content -> jcr:content). */
    static String jcrPath(Path file) {
        String unix = file.toString().replace('\\', '/');
        int index = unix.indexOf("/jcr_root/");
        String relative = unix.substring(index + "/jcr_root".length());
        StringBuilder sb = new StringBuilder();
        for (String segment : relative.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append('/').append(decodeName(segment));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    static String decodeName(String segment) {
        Matcher m = Pattern.compile("^_([a-zA-Z]+)_(.+)$").matcher(segment);
        String name = m.matches() ? m.group(1) + ":" + m.group(2) : segment;
        try {
            return java.net.URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    static void parseContentXml(Path file, String jcrPath, String xml, List<ProjectModel.Component> components,
                                List<ProjectModel.ClientLib> clientLibs) {
        int rootStart = xml.indexOf("<jcr:root");
        if (rootStart < 0) {
            return;
        }
        int rootEnd = xml.indexOf('>', rootStart);
        String rootTag = rootEnd > 0 ? xml.substring(rootStart, rootEnd) : xml.substring(rootStart);
        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
        Matcher m = XML_ATTR.matcher(rootTag);
        while (m.find()) {
            attrs.put(m.group(1), unescapeXml(m.group(2)));
        }
        String primaryType = attrs.get("jcr:primaryType");
        String folderPath = jcrPath.endsWith("/.content.xml")
                ? jcrPath.substring(0, jcrPath.length() - "/.content.xml".length()) : jcrPath;
        if ("cq:Component".equals(primaryType)) {
            ProjectModel.Component component = new ProjectModel.Component();
            component.folder = file.getParent();
            component.resourceType = folderPath.startsWith("/apps/") ? folderPath.substring("/apps/".length()) : folderPath;
            component.title = attrs.get("jcr:title");
            component.superType = attrs.get("sling:resourceSuperType");
            component.group = attrs.get("componentGroup");
            component.hasDialog = Files.isDirectory(file.getParent().resolve("_cq_dialog"));
            components.add(component);
        } else if ("cq:ClientLibraryFolder".equals(primaryType)) {
            ProjectModel.ClientLib lib = new ProjectModel.ClientLib();
            lib.folder = file.getParent();
            lib.jcrPath = folderPath;
            String categories = attrs.getOrDefault("categories", "");
            for (String category : stripType(categories).replace("[", "").replace("]", "").split(",")) {
                if (!category.isBlank()) {
                    lib.categories.add(category.trim());
                }
            }
            lib.allowProxy = stripType(attrs.getOrDefault("allowProxy", "false")).trim().equalsIgnoreCase("true");
            clientLibs.add(lib);
        }
    }

    private static String stripType(String value) {
        return value.replaceFirst("^\\{[A-Za-z]+}", "");
    }

    private static String unescapeXml(String value) {
        return value.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    static ProjectModel.HtlScript parseHtl(Path file, String jcrPath, String html) {
        ProjectModel.HtlScript script = new ProjectModel.HtlScript();
        script.file = file;
        script.jcrPath = jcrPath;
        String folder = jcrPath.substring(0, jcrPath.lastIndexOf('/'));
        script.resourceType = folder.startsWith("/apps/") ? folder.substring("/apps/".length()) : folder;
        Matcher m = HTL_USE.matcher(html);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            if (value.contains("${")) {
                Matcher quoted = SINGLE_QUOTED.matcher(value);
                if (quoted.find()) {
                    value = quoted.group(1);
                }
            }
            script.useObjects.put(m.group(1), value.trim());
        }
        return script;
    }

    static boolean isOsgiConfig(Path file, String jcrPath, String name) {
        Path parent = file.getParent();
        if (parent == null || parent.getFileName() == null) {
            return false;
        }
        String folder = parent.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(folder.equals("config") || folder.startsWith("config."))) {
            return false;
        }
        return name.endsWith(".cfg.json") || name.endsWith(".config") || name.endsWith(".cfg")
                || (name.endsWith(".xml") && !name.equals(".content.xml"));
    }

    static ProjectModel.OsgiConfigFile osgiConfig(Path file, String jcrPath, String name) {
        ProjectModel.OsgiConfigFile config = new ProjectModel.OsgiConfigFile();
        config.file = file;
        config.jcrPath = jcrPath;
        String base = name;
        for (String suffix : new String[] {".cfg.json", ".config", ".cfg", ".xml"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        config.name = base;
        int tilde = base.indexOf('~');
        config.pid = tilde > 0 ? base.substring(0, tilde) : base;
        String folder = file.getParent().getFileName().toString();
        config.runModes = folder.equalsIgnoreCase("config") ? "all" : folder.substring("config.".length());
        return config;
    }
}
