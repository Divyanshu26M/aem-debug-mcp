package dev.aemdebug.jdi;

import dev.aemdebug.correlate.RequestKey;
import dev.aemdebug.fixture.FixtureApp;
import dev.aemdebug.project.ClassFileInfo;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the snapshot engine against a real JVM: the fixture app, started with a JDWP agent on a free port. */
class JdiEngineFixtureTest {

    private static final Pattern JDWP_PORT =
            Pattern.compile("Listening for transport dt_socket at address: (?:\\S*:)?(\\d+)");
    private static final Pattern HTTP_PORT = Pattern.compile("FIXTURE_PORT=(\\d+)");
    private static final String MODELS = "dev.aemdebug.fixture.models.";
    private static final StringBuffer OUTPUT = new StringBuffer();

    private static Process fixture;
    private static int jdwpPort;
    private static int httpPort;
    private static Path classesDir;
    private static JdiEngine engine;
    private static HttpClient http;

    @BeforeAll
    static void startFixture() throws Exception {
        assumeTrue(JdiSupport.available(), "jdk.jdi is not available in this runtime");
        classesDir = Paths.get(FixtureApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        int freePort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            freePort = socket.getLocalPort();
        }
        // A fixed port: with port 0 the agent listens on a new ephemeral port after every detach.
        ProcessBuilder builder = new ProcessBuilder(javaExe,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + freePort,
                "-Xmx128m", "-cp", classesDir.toString(), FixtureApp.class.getName());
        builder.redirectErrorStream(true);
        fixture = builder.start();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Process process = fixture;
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    OUTPUT.append(line).append('\n');
                    lines.add(line);
                }
            } catch (IOException e) {
                OUTPUT.append("(output closed: ").append(e.getMessage()).append(")\n");
            }
        }, "fixture-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + 30_000;
        while ((jdwpPort == 0 || httpPort == 0) && System.currentTimeMillis() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            Matcher jdwp = JDWP_PORT.matcher(line);
            if (jdwp.find()) {
                jdwpPort = Integer.parseInt(jdwp.group(1));
            }
            Matcher port = HTTP_PORT.matcher(line);
            if (port.find()) {
                httpPort = Integer.parseInt(port.group(1));
            }
        }
        assertThat(jdwpPort).as("JDWP port; fixture output:%n%s", OUTPUT).isPositive();
        assertThat(httpPort).as("HTTP port; fixture output:%n%s", OUTPUT).isPositive();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        engine = new JdiEngine(30);
        attach();
    }

    @AfterAll
    static void stopFixture() throws Exception {
        if (engine != null) {
            engine.detach("tests finished");
        }
        if (fixture != null) {
            try {
                fixture.getOutputStream().close();
            } catch (IOException e) {
                // already gone
            }
            if (!fixture.waitFor(10, TimeUnit.SECONDS)) {
                fixture.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @BeforeEach
    void ensureAttached() throws Exception {
        if (!engine.isAttached()) {
            attach();
        }
    }

    @AfterEach
    void reset() {
        engine.removeAll();
        engine.snapshots().clear();
    }

    @Test
    void breakpointCapturesThisFieldsAndTheSlingRequest() throws Exception {
        get("/content/fixture/hero.html");
        int line = line("HeroModel.java", "hero-title");
        BreakpointSpec spec = engine.addBreakpoint(BreakpointSpec.parse(MODELS + "HeroModel:" + line));
        assertThat(spec.id).startsWith("bp");
        assertThat(spec.state()).as(spec.describe()).startsWith("armed");

        assertThat(get("/content/fixture/hero.html")).isEqualTo("<h1>null</h1>");
        List<Snapshot> snapshots = await(from(spec.id), 1);
        assertThat(snapshots).as(engine.status()).hasSize(1);
        Snapshot snapshot = snapshots.get(0);
        assertThat(snapshot.kind).isEqualTo(Snapshot.Kind.BREAKPOINT);
        assertThat(snapshot.line).isEqualTo(line);
        assertThat(snapshot.location).contains("HeroModel");
        assertThat(snapshot.stack).isNotEmpty();
        RequestKey key = snapshot.requestKey;
        assertThat(key).as(snapshot.threadName).isNotNull();
        assertThat(key.method()).isEqualTo("GET");
        assertThat(key.uri()).isEqualTo("/content/fixture/hero.html");

        Snapshot.Frame frame = snapshot.frames.get(0);
        assertThat(frame.thisType).contains("HeroModel");
        assertThat(value(frame.thisFields, "title")).contains("null");
        assertThat(value(frame.thisFields, "subtitle")).contains("Hello there");
        assertThat(value(frame.thisFields, "renderCount")).contains("1");
        assertThat(value(frame.thisFields, "resource")).contains("/content/fixture/jcr:content/root/hero");

        String everything = snapshot.format(100) + Json.write(snapshot.toMap());
        assertThat(everything).as("secrets are masked").doesNotContain("s3cret");
        if (frame.thisId != null) {
            assertThat(engine.expand(frame.thisId, 1)).contains("title");
        }
    }

    @Test
    void conditionLimitsHitsAndLocalsAreCaptured() throws Exception {
        get("/content/fixture/loop.html");
        BreakpointSpec spec = BreakpointSpec.parse(MODELS + "LoopModel:" + line("LoopModel.java", "loop-body"));
        spec.condition = "i == 7";
        engine.addBreakpoint(spec);

        assertThat(get("/content/fixture/loop.html")).isEqualTo("sum=1225");
        await(from(spec.id), 1);
        Thread.sleep(300);
        List<Snapshot> snapshots = engine.snapshots().list(from(spec.id));
        assertThat(snapshots).as(engine.status()).hasSize(1);
        Map<String, String> locals = snapshots.get(0).frames.get(0).locals;
        assertThat(value(locals, "i")).isEqualTo("7");
        assertThat(value(locals, "total")).isEqualTo("21");
        assertThat(spec.describe()).contains("hits 1/50");
    }

    @Test
    void logpointFormatsItsMessage() throws Exception {
        get("/content/fixture/loop.html");
        BreakpointSpec spec = BreakpointSpec.parse(MODELS + "LoopModel:" + line("LoopModel.java", "loop-body"));
        spec.condition = "i == 7";
        spec.logMessage = "i={i} total={total}";
        engine.addBreakpoint(spec);

        assertThat(get("/content/fixture/loop.html")).isEqualTo("sum=1225");
        List<Snapshot> snapshots = await(from(spec.id), 1);
        assertThat(snapshots).as(engine.status()).hasSize(1);
        assertThat(snapshots.get(0).kind).isEqualTo(Snapshot.Kind.LOGPOINT);
        assertThat(snapshots.get(0).message).contains("i=7 total=21");
    }

    @Test
    void maxHitsDisablesTheBreakpointWhileTheApplicationKeepsRunning() throws Exception {
        get("/content/fixture/loop.html");
        BreakpointSpec spec = BreakpointSpec.parse(MODELS + "LoopModel:" + line("LoopModel.java", "loop-body"));
        spec.maxHits = 5;
        engine.addBreakpoint(spec);

        assertThat(get("/content/fixture/loop.html")).isEqualTo("sum=1225");
        await(from(spec.id), 5);
        Thread.sleep(300);
        assertThat(engine.snapshots().list(from(spec.id))).hasSize(5);
        assertThat(spec.state()).as(spec.describe()).startsWith("disabled");
        assertThat(get("/content/fixture/loop.html")).isEqualTo("sum=1225");
    }

    @Test
    void exceptionWatchCapturesCaughtExceptionsAtTheThrowSite() throws Exception {
        ExceptionWatchSpec watch = new ExceptionWatchSpec();
        watch.packages.add(ExceptionWatchSpec.toPattern("dev.aemdebug.fixture"));
        watch.requestFilter = "requests";
        engine.addExceptionWatch(watch);

        assertThat(get("/content/fixture/error.html")).startsWith("caught: Could not inject field 'title'");
        List<Snapshot> snapshots = await(from(watch.id), 1);
        assertThat(snapshots).as(engine.status()).isNotEmpty();
        Snapshot snapshot = snapshots.get(0);
        assertThat(snapshot.kind).isEqualTo(Snapshot.Kind.EXCEPTION);
        assertThat(snapshot.exceptionClass).isEqualTo("java.lang.IllegalStateException");
        assertThat(snapshot.exceptionMessage).contains("Could not inject field 'title'");
        assertThat(snapshot.line).isEqualTo(line("ErrorModel.java", "error-throw"));
        assertThat(snapshot.catchLocation).contains("ErrorModel");
        assertThat(snapshot.requestKey).isNotNull();
        assertThat(snapshot.requestKey.uri()).isEqualTo("/content/fixture/error.html");
        assertThat(value(snapshot.frames.get(0).locals, "field")).contains("title");
    }

    @Test
    void exceptionWatchIgnoresOtherPackages() throws Exception {
        ExceptionWatchSpec watch = new ExceptionWatchSpec();
        watch.packages.add(ExceptionWatchSpec.toPattern("com.example.nothing"));
        engine.addExceptionWatch(watch);

        assertThat(get("/content/fixture/error.html")).startsWith("caught:");
        Thread.sleep(700);
        assertThat(engine.snapshots().list(from(watch.id))).isEmpty();
    }

    @Test
    void requestFilterSkipsOtherRequests() throws Exception {
        get("/content/fixture/hero.html");
        int line = line("HeroModel.java", "hero-title");
        BreakpointSpec other = BreakpointSpec.parse(MODELS + "HeroModel:" + line);
        other.requestFilter = "/content/fixture/other";
        engine.addBreakpoint(other);
        BreakpointSpec hero = BreakpointSpec.parse(MODELS + "HeroModel:" + line);
        hero.requestFilter = "^/content/fixture/hero";
        engine.addBreakpoint(hero);

        get("/content/fixture/hero.html");
        assertThat(await(from(hero.id), 1)).hasSize(1);
        Thread.sleep(300);
        assertThat(engine.snapshots().list(from(other.id))).isEmpty();
        assertThat(other.describe()).contains("(1 skipped)");
    }

    @Test
    void twoBreakpointsOnTheSameLineBothCapture() throws Exception {
        get("/content/fixture/hero.html");
        int line = line("HeroModel.java", "hero-title");
        BreakpointSpec byClass = engine.addBreakpoint(BreakpointSpec.parse(MODELS + "HeroModel:" + line));
        BreakpointSpec byFile = engine.addBreakpoint(BreakpointSpec.parse("HeroModel.java:" + line));
        assertThat(byFile.state()).as(byFile.describe()).startsWith("armed");

        get("/content/fixture/hero.html");
        assertThat(await(from(byClass.id), 1)).as(engine.status()).hasSize(1);
        assertThat(await(from(byFile.id), 1)).as(engine.status()).hasSize(1);
    }

    @Test
    void deferredBreakpointArmsWhenTheClassIsLoaded() throws Exception {
        BreakpointSpec spec = engine.addBreakpoint(
                BreakpointSpec.parse("LazyModel.java:" + line("LazyModel.java", "lazy-value")));
        assertThat(spec.kind).isEqualTo(BreakpointSpec.Kind.FILE_LINE);
        assertThat(spec.state()).as(spec.describe()).startsWith("pending");

        assertThat(get("/content/fixture/lazy.html")).isEqualTo("lazy!");
        List<Snapshot> snapshots = await(from(spec.id), 1);
        if (snapshots.isEmpty()) {
            get("/content/fixture/lazy.html");
            snapshots = await(from(spec.id), 1);
        }
        assertThat(snapshots).as(spec.describe()).isNotEmpty();
        assertThat(spec.state()).startsWith("armed");
        assertThat(snapshots.get(0).frames.get(0).thisType).contains("LazyModel");
    }

    @Test
    void detachReleasesThePortAndBreakpointsSurviveReattach() throws Exception {
        get("/content/fixture/hero.html");
        BreakpointSpec spec = engine.addBreakpoint(
                BreakpointSpec.parse(MODELS + "HeroModel:" + line("HeroModel.java", "hero-title")));
        assertThat(engine.status()).startsWith("Attached to");

        assertThat(engine.detach("test")).isNotBlank();
        assertThat(engine.isAttached()).isFalse();
        assertThat(get("/ping")).isEqualTo("pong");
        assertThat(engine.snapshots().list(from(spec.id))).isEmpty();

        attach();
        assertThat(engine.attach("127.0.0.1", jdwpPort, 5000)).startsWith("Already attached");
        get("/content/fixture/hero.html");
        assertThat(await(from(spec.id), 1)).as(engine.status()).hasSize(1);
    }

    @Test
    void classVersionsMatchTheLocalClassFile() throws Exception {
        get("/content/fixture/hero.html");
        List<JdiEngine.ClassVersion> versions = engine.classVersions(MODELS + "HeroModel");
        assertThat(versions).hasSize(1);
        JdiEngine.ClassVersion version = versions.get(0);
        assertThat(version.sourceName).isEqualTo("HeroModel.java");
        assertThat(version.firstLine).isLessThanOrEqualTo(line("HeroModel.java", "hero-init"));
        assertThat(version.lastLine).isGreaterThanOrEqualTo(line("HeroModel.java", "hero-title"));

        ClassFileInfo local = ClassFileInfo.read(classesDir.resolve("dev/aemdebug/fixture/models/HeroModel.class"));
        int compared = 0;
        for (ClassFileInfo.MethodLines method : local.methods.values()) {
            if (method.lines.isEmpty()) {
                continue;
            }
            int[] deployed = version.methodLines.get(method.name + method.descriptor);
            assertThat(deployed).as(method.name + method.descriptor + " in " + version.methodLines.keySet()).isNotNull();
            assertThat(deployed[0]).isEqualTo(method.first());
            assertThat(deployed[1]).isEqualTo(method.last());
            compared++;
        }
        assertThat(compared).isGreaterThanOrEqualTo(4);

        assertThat(engine.classVersions("HeroModel")).extracting(v -> v.name).contains(MODELS + "HeroModel");
    }

    private static void attach() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            try {
                engine.attach("127.0.0.1", jdwpPort, 5000);
                return;
            } catch (ToolException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(200);
            }
        }
    }

    private static String get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path))
                .timeout(Duration.ofSeconds(30)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static int line(String file, String marker) throws IOException {
        Path source = Paths.get(System.getProperty("basedir", "."))
                .resolve("fixture-app/src/main/java/dev/aemdebug/fixture/models").resolve(file);
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("// BP:" + marker)) {
                return i + 1;
            }
        }
        throw new AssertionError("marker BP:" + marker + " not found in " + source);
    }

    private static List<Snapshot> await(Predicate<Snapshot> filter, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<Snapshot> found = engine.snapshots().list(filter);
        while (found.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            found = engine.snapshots().list(filter);
        }
        return found;
    }

    private static Predicate<Snapshot> from(String sourceId) {
        return snapshot -> sourceId.equals(snapshot.sourceId);
    }

    /** Value of a captured variable; tolerates keys decorated with a type, e.g. "title (String)". */
    private static String value(Map<String, String> values, String name) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key.equals(name) || key.startsWith(name + " ") || key.startsWith(name + ":")) {
                return entry.getValue();
            }
        }
        throw new AssertionError("no value '" + name + "' in " + values);
    }
}
