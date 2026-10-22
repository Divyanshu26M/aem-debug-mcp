package dev.aemdebug.fixture.models;

/** Only loaded (via reflection) when /content/fixture/lazy.html is requested: deferred breakpoint tests. */
public final class LazyModel {

    public String value() {
        String value = "lazy"; // BP:lazy-value
        return value + "!";
    }
}
