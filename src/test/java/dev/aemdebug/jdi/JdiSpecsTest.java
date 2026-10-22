package dev.aemdebug.jdi;

import dev.aemdebug.correlate.RequestKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdiSpecsTest {

    @Test
    void parsesClassLineLocations() {
        BreakpointSpec spec = BreakpointSpec.parse(" com.mysite.core.models.HeroModel:41 ");
        assertThat(spec.kind).isEqualTo(BreakpointSpec.Kind.CLASS_LINE);
        assertThat(spec.className).isEqualTo("com.mysite.core.models.HeroModel");
        assertThat(spec.line).isEqualTo(41);
        assertThat(spec.simpleClassName()).isEqualTo("HeroModel");
        assertThat(spec.hasPackage()).isTrue();

        BreakpointSpec inner = BreakpointSpec.parse("com.mysite.Outer$Inner:5");
        assertThat(inner.simpleClassName()).isEqualTo("Outer");
    }

    @Test
    void parsesFileLineLocations() {
        BreakpointSpec file = BreakpointSpec.parse("HeroModel.java:41");
        assertThat(file.kind).isEqualTo(BreakpointSpec.Kind.FILE_LINE);
        assertThat(file.fileName).isEqualTo("HeroModel.java");
        assertThat(file.className).isNull();
        assertThat(file.simpleClassName()).isEqualTo("HeroModel");

        assertThat(BreakpointSpec.parse("core/src/main/java/com/mysite/HeroModel.java:12").fileName)
                .isEqualTo("HeroModel.java");
        BreakpointSpec windows = BreakpointSpec.parse("C:\\work\\core\\HeroModel.java:7");
        assertThat(windows.kind).isEqualTo(BreakpointSpec.Kind.FILE_LINE);
        assertThat(windows.fileName).isEqualTo("HeroModel.java");
        assertThat(windows.line).isEqualTo(7);
    }

    @Test
    void parsesMethodLocations() {
        BreakpointSpec hash = BreakpointSpec.parse("com.mysite.core.models.HeroModel#init");
        assertThat(hash.kind).isEqualTo(BreakpointSpec.Kind.METHOD);
        assertThat(hash.className).isEqualTo("com.mysite.core.models.HeroModel");
        assertThat(hash.methodName).isEqualTo("init");

        BreakpointSpec call = BreakpointSpec.parse("HeroModel.init()");
        assertThat(call.kind).isEqualTo(BreakpointSpec.Kind.METHOD);
        assertThat(call.className).isEqualTo("HeroModel");
        assertThat(call.hasPackage()).isFalse();
    }

    @Test
    void rejectsUnparseableLocations() {
        assertThatThrownBy(() -> BreakpointSpec.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("HeroModel")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("com.mysite.HeroModel.init"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void describesBreakpointsAndWatches() {
        BreakpointSpec spec = BreakpointSpec.parse("com.mysite.Foo:3");
        spec.id = "bp1";
        assertThat(spec.describe())
                .isEqualTo("bp1 com.mysite.Foo:3 - pending (class not loaded yet or not attached), hits 0/50");
        spec.logMessage = "x={x}";
        assertThat(spec.isLogpoint()).isTrue();
        assertThat(spec.describe()).startsWith("bp1 logpoint com.mysite.Foo:3").endsWith("message \"x={x}\"");

        ExceptionWatchSpec watch = new ExceptionWatchSpec();
        watch.id = "ex1";
        watch.packages.add("com.mysite.*");
        assertThat(watch.describe()).isEqualTo(
                "ex1 java.lang.Throwable thrown in com.mysite.* (caught+uncaught) - not armed (not attached), hits 0/100");
    }

    @Test
    void turnsPackagesIntoClassPatterns() {
        assertThat(ExceptionWatchSpec.toPattern("com.mysite")).isEqualTo("com.mysite.*");
        assertThat(ExceptionWatchSpec.toPattern(" org.apache.sling.models ")).isEqualTo("org.apache.sling.models.*");
        assertThat(ExceptionWatchSpec.toPattern("com.mysite.*")).isEqualTo("com.mysite.*");
        assertThat(ExceptionWatchSpec.toPattern("*.HeroModel")).isEqualTo("*.HeroModel");
        assertThat(ExceptionWatchSpec.toPattern("com.mysite.core.models.HeroModel"))
                .isEqualTo("com.mysite.core.models.HeroModel");
        assertThat(ExceptionWatchSpec.toPattern("")).isEmpty();
    }

    @Test
    void matchesRequestFilters() {
        RequestKey key = new RequestKey("127.0.0.1", 1_700_000_000_000L, "GET", "/content/site/en/hero.html",
                "HTTP/1.1");
        assertThat(JdiEngine.requestMatches(null, null)).isTrue();
        assertThat(JdiEngine.requestMatches("*", null)).isTrue();
        assertThat(JdiEngine.requestMatches("requests", key)).isTrue();
        assertThat(JdiEngine.requestMatches("requests", null)).isFalse();
        assertThat(JdiEngine.requestMatches("hero", key)).isTrue();
        assertThat(JdiEngine.requestMatches("footer", key)).isFalse();
        assertThat(JdiEngine.requestMatches("^/content/.*\\.html$", key)).isTrue();
        assertThat(JdiEngine.requestMatches("^/bin/", key)).isFalse();
        assertThat(JdiEngine.requestMatches("^(", key)).isFalse();
    }

    @Test
    void snapshotStoreKeepsTheNewestAndFilters() {
        SnapshotStore store = new SnapshotStore(2);
        Snapshot a = snapshot("t1", Snapshot.Kind.BREAKPOINT, 1000);
        Snapshot b = snapshot("t2", Snapshot.Kind.EXCEPTION, 2000);
        Snapshot c = snapshot("t3", Snapshot.Kind.LOGPOINT, 3000);
        store.add(a);
        store.add(b);
        store.add(c);
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.dropped()).isEqualTo(1);
        assertThat(store.list(snapshot -> true)).containsExactly(b, c);
        assertThat(store.list(Snapshot::isException)).containsExactly(b);
        assertThat(store.since(2500)).containsExactly(c);
        assertThat(store.get(c.id)).isSameAs(c);
        assertThat(store.clear()).isEqualTo(2);
        assertThat(store.size()).isZero();
    }

    private static Snapshot snapshot(String id, Snapshot.Kind kind, long time) {
        Snapshot snapshot = new Snapshot();
        snapshot.id = id;
        snapshot.kind = kind;
        snapshot.time = time;
        snapshot.sourceId = "bp1";
        snapshot.location = "com.mysite.Foo.bar(Foo.java:3)";
        return snapshot;
    }
}
