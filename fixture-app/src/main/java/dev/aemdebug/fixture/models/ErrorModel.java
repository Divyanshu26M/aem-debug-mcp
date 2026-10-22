package dev.aemdebug.fixture.models;

/** Throws an exception that is caught again, like a Sling Model injection failure. */
public final class ErrorModel {

    public static String render(String path) {
        try {
            return new ErrorModel().compute(path);
        } catch (IllegalStateException e) {
            return "caught: " + e.getMessage();
        }
    }

    String compute(String path) {
        String field = "title";
        throw new IllegalStateException("Could not inject field '" + field + "' for " + path); // BP:error-throw
    }
}
