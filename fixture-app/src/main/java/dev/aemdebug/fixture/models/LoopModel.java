package dev.aemdebug.fixture.models;

/** Hits the same line many times per request (flood protection tests). */
public final class LoopModel {

    private LoopModel() {
    }

    public static int sum(int n) {
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += i; // BP:loop-body
        }
        return total;
    }
}
