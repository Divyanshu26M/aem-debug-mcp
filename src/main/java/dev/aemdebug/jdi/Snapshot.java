package dev.aemdebug.jdi;

import dev.aemdebug.correlate.RequestKey;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Data captured when a breakpoint, logpoint or exception watch fired. */
public final class Snapshot {

    /** What produced the snapshot. */
    public enum Kind {
        BREAKPOINT, LOGPOINT, EXCEPTION
    }

    /** A stack frame with captured variables. */
    public static final class Frame {
        public String location;
        public final Map<String, String> locals = new LinkedHashMap<>();
        public String thisType;
        public Long thisId;
        public final Map<String, String> thisFields = new LinkedHashMap<>();
        public String note;
    }

    public String id;
    public Kind kind;
    public String sourceId;
    public long time;
    public String threadName;
    public RequestKey requestKey;
    public String location;
    public String className;
    public String methodName;
    public String sourceName;
    public int line;
    public final List<String> stack = new ArrayList<>();
    public final List<Frame> frames = new ArrayList<>();
    public String message;
    public String exceptionClass;
    public String exceptionMessage;
    public String catchLocation;
    public final List<String> causes = new ArrayList<>();
    public final Map<String, String> exceptionFields = new LinkedHashMap<>();
    public final List<String> notes = new ArrayList<>();

    public boolean isException() {
        return kind == Kind.EXCEPTION;
    }

    /** One-line summary for lists. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(' ').append(Text.formatTime(time)).append(' ');
        switch (kind) {
            case EXCEPTION:
                sb.append(catchLocation == null ? "UNCAUGHT " : "caught ").append(exceptionClass);
                if (exceptionMessage != null) {
                    sb.append(": ").append(Text.oneLine(exceptionMessage, 140));
                }
                sb.append(" @ ").append(location);
                break;
            case LOGPOINT:
                sb.append("log ").append(sourceId).append(" @ ").append(location).append(": ")
                        .append(Text.oneLine(message, 200));
                break;
            default:
                sb.append("snapshot ").append(sourceId).append(" @ ").append(location);
                break;
        }
        if (requestKey != null) {
            sb.append(" [").append(requestKey.method()).append(' ').append(requestKey.uri()).append(']');
        } else if (threadName != null) {
            sb.append(" [thread ").append(Text.oneLine(threadName, 60)).append(']');
        }
        return sb.toString();
    }

    /** Multi-line rendering with variables and stack. */
    public String format(int maxStackLines) {
        StringBuilder sb = new StringBuilder();
        sb.append(summary()).append('\n');
        if (requestKey != null) {
            sb.append("  request: ").append(requestKey.label()).append('\n');
        } else {
            sb.append("  thread: ").append(threadName).append('\n');
        }
        if (kind == Kind.EXCEPTION) {
            sb.append("  thrown at ").append(location).append(catchLocation == null ? " (not caught)"
                    : ", caught at " + catchLocation).append('\n');
            for (String cause : causes) {
                sb.append("  caused by ").append(cause).append('\n');
            }
            for (Map.Entry<String, String> field : exceptionFields.entrySet()) {
                sb.append("  exception.").append(field.getKey()).append(" = ").append(field.getValue()).append('\n');
            }
        }
        for (Frame frame : frames) {
            sb.append("  frame ").append(frame.location).append('\n');
            if (frame.note != null) {
                sb.append("    (").append(frame.note).append(")\n");
            }
            if (frame.locals.isEmpty()) {
                sb.append("    locals: (none visible)\n");
            } else {
                sb.append("    locals:\n");
                for (Map.Entry<String, String> local : frame.locals.entrySet()) {
                    sb.append("      ").append(local.getKey()).append(" = ").append(local.getValue()).append('\n');
                }
            }
            if (frame.thisType != null) {
                sb.append("    this: ").append(frame.thisType).append(frame.thisId == null ? "" : " #obj" + frame.thisId)
                        .append('\n');
                for (Map.Entry<String, String> field : frame.thisFields.entrySet()) {
                    sb.append("      ").append(field.getKey()).append(" = ").append(field.getValue()).append('\n');
                }
            }
        }
        if (!stack.isEmpty()) {
            sb.append("  stack:\n");
            for (int i = 0; i < stack.size() && i < maxStackLines; i++) {
                sb.append("    at ").append(stack.get(i)).append('\n');
            }
            if (stack.size() > maxStackLines) {
                sb.append("    ... ").append(stack.size() - maxStackLines).append(" more frames\n");
            }
        }
        for (String note : notes) {
            sb.append("  note: ").append(note).append('\n');
        }
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("kind", kind.name());
        map.put("source", sourceId);
        map.put("time", time);
        map.put("thread", threadName);
        map.put("request", requestKey == null ? null : requestKey.threadName());
        map.put("location", location);
        map.put("message", message);
        map.put("exceptionClass", exceptionClass);
        map.put("exceptionMessage", exceptionMessage);
        map.put("catchLocation", catchLocation);
        map.put("causes", causes);
        map.put("exceptionFields", exceptionFields);
        List<Object> frameMaps = new ArrayList<>();
        for (Frame frame : frames) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("location", frame.location);
            f.put("locals", frame.locals);
            f.put("this", frame.thisType);
            f.put("thisFields", frame.thisFields);
            frameMaps.add(f);
        }
        map.put("frames", frameMaps);
        map.put("stack", stack);
        map.put("notes", notes);
        return map;
    }
}
