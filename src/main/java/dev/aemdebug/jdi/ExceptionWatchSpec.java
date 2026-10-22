package dev.aemdebug.jdi;

import com.sun.jdi.request.EventRequest;

import java.util.ArrayList;
import java.util.List;

/** Exception capture at the throw site, scoped by throw-location class patterns and a request filter. */
public final class ExceptionWatchSpec {

    public String id;
    public String exceptionClass = "java.lang.Throwable";
    public boolean caught = true;
    public boolean uncaught = true;
    /** JDI class patterns of the throw location, e.g. com.mysite.* (exact names or leading/trailing *). */
    public final List<String> packages = new ArrayList<>();
    public String requestFilter;
    public int maxHits = 100;
    public int maxPerSecond = 30;
    public int frames = 15;
    public int localsFrames = 1;
    public int depth = 2;
    public boolean temporary;

    final List<EventRequest> requests = new ArrayList<>();
    int hits;
    int filteredOut;
    int otherExceptions;
    String disabledReason;
    String lastError;
    long windowStart;
    int windowEvents;

    /** Turns "com.mysite" into "com.mysite.*"; keeps explicit patterns and class names. */
    public static String toPattern(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.startsWith("*") || trimmed.endsWith("*")) {
            return trimmed;
        }
        String last = trimmed.substring(trimmed.lastIndexOf('.') + 1);
        boolean looksLikeClass = !last.isEmpty() && Character.isUpperCase(last.charAt(0));
        return looksLikeClass ? trimmed : trimmed + ".*";
    }

    public synchronized String state() {
        if (disabledReason != null) {
            return "disabled: " + disabledReason;
        }
        return requests.isEmpty() ? "not armed (not attached)" : "armed";
    }

    public synchronized String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(' ').append(exceptionClass).append(" thrown in ")
                .append(packages.isEmpty() ? "any class" : String.join(", ", packages)).append(" (")
                .append(caught && uncaught ? "caught+uncaught" : caught ? "caught only" : "uncaught only")
                .append(") - ").append(state()).append(", hits ").append(hits).append('/').append(maxHits);
        if (requestFilter != null) {
            sb.append(", request filter '").append(requestFilter).append("' (").append(filteredOut).append(" skipped)");
        }
        if (lastError != null) {
            sb.append("\n    problem: ").append(lastError);
        }
        return sb.toString();
    }
}
