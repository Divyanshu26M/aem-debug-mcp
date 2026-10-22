package dev.aemdebug.browser;

/** A console message or uncaught page error. */
public final class ConsoleEntry {

    private final String type;
    private final String text;
    private final String location;
    private final long at;

    public ConsoleEntry(String type, String text, String location, long at) {
        this.type = type;
        this.text = text;
        this.location = location;
        this.at = at;
    }

    public String type() {
        return type;
    }

    public String text() {
        return text;
    }

    public String location() {
        return location;
    }

    public long at() {
        return at;
    }

    public boolean isError() {
        return "error".equals(type) || "pageerror".equals(type);
    }

    public boolean isWarning() {
        return "warning".equals(type);
    }
}
