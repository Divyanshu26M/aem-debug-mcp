package dev.aemdebug.jdi;

import com.sun.jdi.request.EventRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A snapshot breakpoint or logpoint: what to capture, where, and its runtime state. */
public final class BreakpointSpec {

    /** How the location was given. */
    public enum Kind {
        CLASS_LINE, FILE_LINE, METHOD
    }

    private static final Pattern LINE_LOCATION = Pattern.compile("^(.+?):(\\d+)$");
    private static final Pattern METHOD_LOCATION = Pattern.compile("^([\\w.$]+)(?:#|\\.)(\\w+)(?:\\(\\))?$");

    public String id;
    public Kind kind;
    public String location;
    public String className;
    public String fileName;
    public final List<String> candidateClasses = new ArrayList<>();
    public int line;
    public String methodName;
    public String condition;
    public Condition compiledCondition;
    public String logMessage;
    public String requestFilter;
    public int maxHits = 50;
    public int maxPerSecond = 20;
    public int frames = 12;
    public int localsFrames = 1;
    public int depth = 2;
    public boolean temporary;

    // runtime state (guarded by this)
    final List<EventRequest> requests = new ArrayList<>();
    final List<EventRequest> prepareRequests = new ArrayList<>();
    final List<String> armedIn = new ArrayList<>();
    int hits;
    int conditionMisses;
    int filteredOut;
    String disabledReason;
    String lastError;
    long windowStart;
    int windowEvents;

    /** Parses "com.foo.Bar:41", "Bar.java:41", "path/to/Bar.java:41", "com.foo.Bar#method" or "Bar.method()". */
    public static BreakpointSpec parse(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location is required: Class:line, File.java:line or Class#method");
        }
        String value = location.trim();
        BreakpointSpec spec = new BreakpointSpec();
        spec.location = value;
        Matcher line = LINE_LOCATION.matcher(value);
        if (line.matches()) {
            String target = line.group(1).trim();
            spec.line = Integer.parseInt(line.group(2));
            String lower = target.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java") || target.contains("/") || target.contains("\\")) {
                spec.kind = Kind.FILE_LINE;
                String normalized = target.replace('\\', '/');
                spec.fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
                if (!spec.fileName.toLowerCase(Locale.ROOT).endsWith(".java")) {
                    spec.fileName = spec.fileName + ".java";
                }
            } else {
                spec.kind = Kind.CLASS_LINE;
                spec.className = target;
            }
            return spec;
        }
        Matcher method = METHOD_LOCATION.matcher(value);
        if (method.matches() && (value.contains("#") || value.endsWith("()"))) {
            spec.kind = Kind.METHOD;
            spec.className = method.group(1);
            spec.methodName = method.group(2);
            return spec;
        }
        throw new IllegalArgumentException("Cannot parse location '" + location
                + "'. Use com.mysite.core.models.HeroModel:41, HeroModel.java:41 or com.mysite.core.models.HeroModel#init");
    }

    /** Simple class name derived from the class or file name. */
    public String simpleClassName() {
        if (className != null) {
            String name = className.substring(className.lastIndexOf('.') + 1);
            return name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
        }
        return fileName == null ? null : fileName.substring(0, fileName.length() - ".java".length());
    }

    public boolean hasPackage() {
        return className != null && className.contains(".");
    }

    public boolean isLogpoint() {
        return logMessage != null;
    }

    public synchronized String state() {
        if (disabledReason != null) {
            return "disabled: " + disabledReason;
        }
        if (!armedIn.isEmpty()) {
            return "armed in " + armedIn.size() + (armedIn.size() == 1 ? " location" : " locations");
        }
        return "pending (class not loaded yet or not attached)";
    }

    public synchronized String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(' ').append(isLogpoint() ? "logpoint " : "").append(location).append(" - ").append(state());
        sb.append(", hits ").append(hits).append('/').append(maxHits);
        if (condition != null) {
            sb.append(", condition [").append(condition).append("] false ").append(conditionMisses).append('x');
        }
        if (requestFilter != null) {
            sb.append(", request filter '").append(requestFilter).append("' (").append(filteredOut).append(" skipped)");
        }
        if (isLogpoint()) {
            sb.append(", message \"").append(logMessage).append('"');
        }
        if (!armedIn.isEmpty()) {
            sb.append("\n    at ").append(String.join("\n    at ", armedIn));
        }
        if (lastError != null) {
            sb.append("\n    problem: ").append(lastError);
        }
        return sb.toString();
    }
}
