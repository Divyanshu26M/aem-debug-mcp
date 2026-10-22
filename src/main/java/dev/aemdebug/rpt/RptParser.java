package dev.aemdebug.rpt;

import dev.aemdebug.util.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Sling RequestProgressTracker dump (from the Recent Requests console or the RPT log filter) into a timer
 * tree, the resolved resource/servlet of the request and the list of included resources with their scripts.
 */
public final class RptParser {

    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\s+(TIMER_START|TIMER_END|LOG|COMMENT)\\s?(.*)$");
    private static final Pattern INCLUDING = Pattern.compile(
            "Including resource\\s+(\\S+?),\\s*type=([^,\\s]+)(?:,\\s*superType=([^,\\s]+))?,\\s*path=([^,\\s(]+)");
    private static final Pattern RESOLVES_TO = Pattern.compile(
            "URI=(\\S+) resolves to Resource=(\\S+?),\\s*type=([^,\\s]+)(?:,\\s*superType=([^,\\s]+))?,\\s*path=([^,\\s(]+)");
    private static final Pattern HANDLED_BY = Pattern.compile("handled by Servlet=(\\S+)");
    private static final Pattern USING_SERVLET = Pattern.compile("Using servlet (\\S+)");
    private static final Pattern RESOLVE_SERVLET = Pattern.compile("^resolveServlet\\((.*)\\)$");
    private static final Pattern METHOD = Pattern.compile("Method=([A-Z]+), PathInfo=(\\S+)");

    /** Timer node. */
    public static final class Node {
        public final String name;
        public final long start;
        public long elapsed = -1;
        public String message;
        public final List<Node> children = new ArrayList<>();
        public final List<String> logs = new ArrayList<>();

        Node(String name, long start) {
            this.name = name;
            this.start = start;
        }
    }

    /** A resource included while rendering (usually a component). */
    public static final class Include {
        public final String resourcePath;
        public final String resourceType;
        public String script;
        public long elapsed = -1;
        public final long at;
        public int depth;

        Include(String resourcePath, String resourceType, long at) {
            this.resourcePath = resourcePath;
            this.resourceType = resourceType;
            this.at = at;
        }
    }

    /** Parse result. */
    public static final class Result {
        public String unit = "ms";
        public String method;
        public String pathInfo;
        public String uri;
        public String resolvedPath;
        public String resolvedType;
        public String servlet;
        public long total = -1;
        public final Node root = new Node("(request)", 0);
        public final List<Include> includes = new ArrayList<>();
        public final List<String> problems = new ArrayList<>();
        public int lineCount;

        public double toMillis(long value) {
            return "us".equals(unit) ? value / 1000.0 : value;
        }

        public String formatDuration(long value) {
            if (value < 0) {
                return "?";
            }
            return String.format(Locale.ROOT, "%.1f ms", toMillis(value));
        }

        /** Script used for a resource path, if it was included. */
        public Map<String, String> scriptsByPath() {
            Map<String, String> map = new LinkedHashMap<>();
            for (Include include : includes) {
                if (include.script != null) {
                    map.putIfAbsent(include.resourcePath, include.script);
                }
            }
            return map;
        }

        /** Indented timer tree with includes; limited to maxLines. */
        public String tree(int maxLines) {
            StringBuilder sb = new StringBuilder();
            int[] count = {0};
            for (Node child : root.children) {
                render(child, 0, sb, count, maxLines);
            }
            if (count[0] >= maxLines) {
                sb.append("... (truncated; full RPT in the artifact)\n");
            }
            return sb.toString();
        }

        private void render(Node node, int depth, StringBuilder sb, int[] count, int maxLines) {
            if (count[0]++ >= maxLines) {
                return;
            }
            sb.append("  ".repeat(depth)).append(node.name).append(' ').append(formatDuration(node.elapsed));
            if (node.message != null && !node.message.isBlank()) {
                sb.append(" - ").append(Text.oneLine(node.message, 160));
            }
            sb.append('\n');
            for (String log : node.logs) {
                if (count[0] >= maxLines) {
                    return;
                }
                if (log.startsWith("Including resource") || isProblem(log)) {
                    count[0]++;
                    sb.append("  ".repeat(depth + 1)).append("* ").append(Text.oneLine(log, 200)).append('\n');
                }
            }
            for (Node child : node.children) {
                render(child, depth + 1, sb, count, maxLines);
            }
        }
    }

    private RptParser() {
    }

    public static Result parse(String text) {
        Result result = new Result();
        if (text == null) {
            return result;
        }
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(result.root);
        Map<String, String> servletByPath = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            Matcher m = LINE.matcher(raw);
            if (!m.matches()) {
                continue;
            }
            result.lineCount++;
            long time = Long.parseLong(m.group(1));
            String type = m.group(2);
            String rest = m.group(3).trim();
            switch (type) {
                case "COMMENT":
                    if (rest.toLowerCase(Locale.ROOT).contains("microsecond")) {
                        result.unit = "us";
                    }
                    break;
                case "TIMER_START": {
                    String name = braces(rest);
                    Node node = new Node(name, time);
                    stack.peek().children.add(node);
                    stack.push(node);
                    break;
                }
                case "TIMER_END": {
                    String inside = braces(rest);
                    String message = afterBraces(rest);
                    int comma = inside.indexOf(',');
                    long elapsed = -1;
                    String name = inside;
                    if (comma > 0) {
                        try {
                            elapsed = Long.parseLong(inside.substring(0, comma).trim());
                        } catch (NumberFormatException e) {
                            elapsed = -1;
                        }
                        name = inside.substring(comma + 1);
                    }
                    Node node = popUntil(stack, name);
                    if (node != null) {
                        node.elapsed = elapsed;
                        node.message = message;
                    }
                    handleTimerEnd(result, name, elapsed, message, servletByPath);
                    break;
                }
                default: {
                    stack.peek().logs.add(rest);
                    handleLog(result, rest, time, stack.size() - 1);
                    break;
                }
            }
        }
        for (Include include : result.includes) {
            if (include.script == null) {
                include.script = servletByPath.get(include.resourcePath);
            }
        }
        assignScriptTimes(result);
        return result;
    }

    private static void handleTimerEnd(Result result, String name, long elapsed, String message,
                                       Map<String, String> servletByPath) {
        if (name.equals("Request Processing")) {
            result.total = elapsed;
        } else if (name.equals("ResourceResolution")) {
            Matcher m = RESOLVES_TO.matcher(message);
            if (m.find()) {
                result.uri = m.group(1);
                result.resolvedType = m.group(3);
                result.resolvedPath = m.group(5);
            }
        } else if (name.equals("ServletResolution")) {
            Matcher m = HANDLED_BY.matcher(message);
            if (m.find()) {
                result.servlet = m.group(1);
            }
        } else {
            Matcher resolve = RESOLVE_SERVLET.matcher(name);
            if (resolve.matches()) {
                Matcher using = USING_SERVLET.matcher(message);
                if (using.find()) {
                    servletByPath.put(resolve.group(1), using.group(1));
                    for (int i = result.includes.size() - 1; i >= 0; i--) {
                        Include include = result.includes.get(i);
                        if (include.resourcePath.equals(resolve.group(1)) && include.script == null) {
                            include.script = using.group(1);
                            break;
                        }
                    }
                }
            }
        }
        if (isProblem(message)) {
            result.problems.add(name + ": " + message);
        }
    }

    private static void handleLog(Result result, String message, long time, int depth) {
        Matcher including = INCLUDING.matcher(message);
        if (including.find()) {
            Include include = new Include(including.group(4), including.group(2), time);
            include.depth = depth;
            result.includes.add(include);
            return;
        }
        Matcher method = METHOD.matcher(message);
        if (method.find()) {
            result.method = method.group(1);
            result.pathInfo = method.group(2);
            return;
        }
        if (isProblem(message)) {
            result.problems.add(message);
        }
    }

    /** Script execution timers are named "{script}#{n}"; attach their elapsed time to the include. */
    private static void assignScriptTimes(Result result) {
        List<Node> scriptNodes = new ArrayList<>();
        collectScriptNodes(result.root, scriptNodes);
        Iterator<Node> nodes = scriptNodes.iterator();
        List<Node> remaining = new ArrayList<>(scriptNodes);
        for (Include include : result.includes) {
            if (include.script == null) {
                continue;
            }
            for (Iterator<Node> it = remaining.iterator(); it.hasNext(); ) {
                Node node = it.next();
                if (node.start >= include.at && node.name.startsWith(include.script + "#")) {
                    include.elapsed = node.elapsed;
                    it.remove();
                    break;
                }
            }
        }
        if (nodes.hasNext()) {
            nodes.next();
        }
    }

    private static void collectScriptNodes(Node node, List<Node> out) {
        for (Node child : node.children) {
            if (child.name.matches(".+#\\d+$")) {
                out.add(child);
            }
            collectScriptNodes(child, out);
        }
    }

    static boolean isProblem(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("exception") || lower.contains(" error") || lower.startsWith("error")
                || lower.contains("failed");
    }

    private static Node popUntil(Deque<Node> stack, String name) {
        for (Iterator<Node> it = stack.iterator(); it.hasNext(); ) {
            Node node = it.next();
            if (node.name.equals(name)) {
                while (!stack.isEmpty() && stack.peek() != node) {
                    stack.pop();
                }
                if (stack.size() > 1) {
                    stack.pop();
                }
                return node;
            }
        }
        return null;
    }

    private static String braces(String rest) {
        if (!rest.startsWith("{")) {
            return rest;
        }
        int close = rest.indexOf('}');
        return close > 0 ? rest.substring(1, close) : rest.substring(1);
    }

    private static String afterBraces(String rest) {
        if (!rest.startsWith("{")) {
            return "";
        }
        int close = rest.indexOf('}');
        return close > 0 && close + 1 < rest.length() ? rest.substring(close + 1).trim() : "";
    }
}
