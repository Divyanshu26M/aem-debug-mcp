package dev.aemdebug.jdi;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.config.HostPolicy;
import dev.aemdebug.correlate.RequestKey;
import dev.aemdebug.server.CleanupCoordinator;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Text;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Snapshot-first JDI engine. Attaches to AEM's JDWP port on demand (exclusive; never suspends the whole VM, never
 * calls VirtualMachine.exit). Breakpoints use SUSPEND_EVENT_THREAD: the hit thread is held only while its stack,
 * locals and 'this' are captured, then resumed. Deferred breakpoints arm on class preparation (also for new class
 * versions after a bundle redeploy). Exception watches capture exceptions at the throw site.
 */
public final class JdiEngine implements CleanupCoordinator.Participant {

    private static final Logger LOG = LoggerFactory.getLogger(JdiEngine.class);

    static final String SPEC_KEY = "aemdebug.spec";
    private static final long INVOKE_TIMEOUT_MS = 1500;
    private static final int MAX_PINNED = 500;

    /** One loaded version of a class. */
    public static final class ClassVersion {
        public String name;
        public String loader;
        public String bundleId;
        public String bundleSymbolicName;
        public int revision;
        public String sourceName;
        public int firstLine = -1;
        public int lastLine = -1;
        public final Map<String, int[]> methodLines = new LinkedHashMap<>();
    }

    private final long idleMillis;
    private final SnapshotStore snapshots = new SnapshotStore(1000);
    private final Map<String, BreakpointSpec> breakpoints = new ConcurrentHashMap<>();
    private final Map<String, ExceptionWatchSpec> watches = new ConcurrentHashMap<>();
    private final AtomicInteger breakpointCounter = new AtomicInteger();
    private final AtomicInteger watchCounter = new AtomicInteger();
    private final Set<Long> invokingThreads = ConcurrentHashMap.newKeySet();
    private final Map<Long, ObjectReference> pinned = new LinkedHashMap<>();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile VirtualMachine vm;
    private volatile String endpoint;
    private volatile long attachedAt;
    private volatile long lastEventAt;
    private volatile boolean running;
    private Thread eventThread;
    private volatile ExecutorService capture;
    private volatile ExecutorService invoker;
    private volatile Field detailMessageField;
    private volatile Field causeField;
    private final String label;

    public JdiEngine(int idleDetachMinutes) {
        this(idleDetachMinutes, null);
    }

    /** An engine for one AEM instance; the label (e.g. "publish") names it in cleanup reports. */
    public JdiEngine(int idleDetachMinutes, String label) {
        this.idleMillis = idleDetachMinutes * 60_000L;
        this.label = label;
    }

    public String label() {
        return label;
    }

    // ------------------------------------------------------------------ connection

    public boolean isAttached() {
        return vm != null;
    }

    public String endpoint() {
        return endpoint;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public String attach(String host, int port, int timeoutMs) {
        synchronized (lock) {
            VirtualMachine current = vm;
            if (current != null) {
                return "Already attached to " + endpoint + " (" + safe(current::name) + ").";
            }
            if (!HostPolicy.isLocalHost(host)) {
                throw new ToolException("JDWP host must be local (localhost, 127.0.0.1, ::1): " + host);
            }
            if (!JdiSupport.available()) {
                throw new ToolException(JdiSupport.unavailableMessage());
            }
            AttachingConnector connector = socketConnector();
            Map<String, Connector.Argument> arguments = connector.defaultArguments();
            arguments.get("hostname").setValue(host);
            arguments.get("port").setValue(String.valueOf(port));
            Connector.Argument timeout = arguments.get("timeout");
            if (timeout != null) {
                timeout.setValue(String.valueOf(timeoutMs));
            }
            VirtualMachine machine;
            try {
                machine = connector.attach(arguments);
            } catch (ConnectException e) {
                throw new ToolException("JDWP port " + host + ":" + port + " refused the connection. Either another "
                        + "debugger is attached (e.g. an IntelliJ 'Remote JVM Debug' session - stop it and retry), or AEM "
                        + "was not started with -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + port + ".");
            } catch (SocketTimeoutException e) {
                throw new ToolException("Timed out attaching to JDWP at " + host + ":" + port + ".");
            } catch (IOException e) {
                throw new ToolException("Could not attach to JDWP at " + host + ":" + port + ": " + e.getMessage()
                        + ". If IntelliJ is attached, detach it first (JDWP accepts a single debugger).");
            } catch (IllegalConnectorArgumentsException e) {
                throw new ToolException("Invalid JDWP connector arguments: " + e.getMessage());
            }
            vm = machine;
            endpoint = host + ":" + port;
            attachedAt = System.currentTimeMillis();
            lastEventAt = attachedAt;
            initThrowableFields(machine);
            capture = Executors.newSingleThreadExecutor(daemon("aemdbg-jdi-capture"));
            invoker = Executors.newCachedThreadPool(daemon("aemdbg-jdi-invoke"));
            running = true;
            Thread thread = new Thread(() -> eventLoop(machine), "aemdbg-jdi-events");
            thread.setDaemon(true);
            eventThread = thread;
            thread.start();
            for (BreakpointSpec spec : breakpointList()) {
                arm(machine, spec);
            }
            for (ExceptionWatchSpec spec : watchList()) {
                armWatch(machine, spec);
            }
            LOG.info("Attached to {} {} at {}", safe(machine::name), safe(machine::version), endpoint);
            return "Attached to " + safe(machine::name) + " " + safe(machine::version) + " at " + endpoint
                    + ". Breakpoints only snapshot the hit thread and resume it immediately; AEM keeps running.";
        }
    }

    public String detach(String reason) {
        synchronized (lock) {
            VirtualMachine machine = vm;
            if (machine == null) {
                return "Not attached.";
            }
            running = false;
            vm = null;
            try {
                EventRequestManager erm = machine.eventRequestManager();
                erm.deleteAllBreakpoints();
                erm.deleteEventRequests(new ArrayList<>(erm.exceptionRequests()));
                erm.deleteEventRequests(new ArrayList<>(erm.classPrepareRequests()));
            } catch (RuntimeException e) {
                LOG.debug("deleting requests failed: {}", e.toString());
            }
            unpinAll();
            for (BreakpointSpec spec : breakpointList()) {
                synchronized (spec) {
                    spec.requests.clear();
                    spec.prepareRequests.clear();
                    spec.armedIn.clear();
                }
            }
            for (ExceptionWatchSpec spec : watchList()) {
                synchronized (spec) {
                    spec.requests.clear();
                }
            }
            ExecutorService captureExecutor = capture;
            if (captureExecutor != null) {
                captureExecutor.shutdown();
                try {
                    captureExecutor.awaitTermination(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                machine.dispose();
            } catch (RuntimeException e) {
                LOG.debug("dispose failed: {}", e.toString());
            }
            Thread thread = eventThread;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ExecutorService invokeExecutor = invoker;
            if (invokeExecutor != null) {
                invokeExecutor.shutdownNow();
            }
            invokingThreads.clear();
            String text = "Detached from " + endpoint + " (" + reason + "). The JDWP port is free again, so IntelliJ "
                    + "can reattach. Breakpoint/watch definitions are kept and re-armed on the next attach.";
            LOG.info("Detached from {}: {}", endpoint, reason);
            endpoint = null;
            return text;
        }
    }

    private AttachingConnector socketConnector() {
        for (AttachingConnector connector : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(connector.name())) {
                return connector;
            }
        }
        throw new ToolException("The JDI socket attach connector is not available in this Java runtime");
    }

    private void initThrowableFields(VirtualMachine machine) {
        try {
            List<ReferenceType> types = machine.classesByName("java.lang.Throwable");
            if (!types.isEmpty()) {
                detailMessageField = types.get(0).fieldByName("detailMessage");
                causeField = types.get(0).fieldByName("cause");
            }
        } catch (RuntimeException e) {
            LOG.debug("Throwable fields not available: {}", e.toString());
        }
    }

    private void onDisconnected(VirtualMachine machine, String reason) {
        if (!running) {
            return;
        }
        synchronized (lock) {
            if (vm != machine) {
                return;
            }
            running = false;
            vm = null;
            synchronized (pinned) {
                pinned.clear();
            }
            for (BreakpointSpec spec : breakpointList()) {
                synchronized (spec) {
                    spec.requests.clear();
                    spec.prepareRequests.clear();
                    spec.armedIn.clear();
                }
            }
            for (ExceptionWatchSpec spec : watchList()) {
                synchronized (spec) {
                    spec.requests.clear();
                }
            }
            if (capture != null) {
                capture.shutdownNow();
            }
            if (invoker != null) {
                invoker.shutdownNow();
            }
            notices.add(Text.formatTime(System.currentTimeMillis()) + " connection to " + endpoint + " lost: " + reason);
            LOG.info("JDWP connection to {} lost: {}", endpoint, reason);
            endpoint = null;
        }
    }

    // ------------------------------------------------------------------ event loop

    private void eventLoop(VirtualMachine machine) {
        EventQueue queue = machine.eventQueue();
        while (running) {
            EventSet set;
            try {
                set = queue.remove(500);
            } catch (InterruptedException e) {
                break;
            } catch (VMDisconnectedException e) {
                onDisconnected(machine, "the AEM JVM disconnected");
                break;
            }
            if (set == null) {
                continue;
            }
            lastEventAt = System.currentTimeMillis();
            boolean handedOff = false;
            try {
                // Several breakpoints/watches can fire at the same location: every event is admitted and captured.
                List<CaptureTask> tasks = new ArrayList<>();
                for (Event event : set) {
                    if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        onDisconnected(machine, "the AEM JVM stopped or disconnected");
                        return;
                    }
                    if (event instanceof ClassPrepareEvent) {
                        onClassPrepare(machine, (ClassPrepareEvent) event);
                    } else if (event instanceof BreakpointEvent || event instanceof ExceptionEvent) {
                        CaptureTask prepared = prepare((LocatableEvent) event);
                        if (prepared != null) {
                            tasks.add(prepared);
                        }
                    }
                }
                ExecutorService executor = capture;
                if (!tasks.isEmpty() && executor != null) {
                    handedOff = submit(executor, set, state -> runAll(tasks, state));
                }
            } catch (VMDisconnectedException e) {
                onDisconnected(machine, "the AEM JVM disconnected");
                return;
            } catch (RuntimeException e) {
                LOG.warn("JDI event handling failed", e);
            } finally {
                if (!handedOff) {
                    resumeQuietly(set);
                }
            }
        }
    }

    private void onClassPrepare(VirtualMachine machine, ClassPrepareEvent event) {
        Object id = event.request().getProperty(SPEC_KEY);
        BreakpointSpec spec = id == null ? null : breakpoints.get(String.valueOf(id));
        if (spec != null) {
            int before;
            synchronized (spec) {
                before = spec.armedIn.size();
            }
            armIn(machine, spec, event.referenceType());
            synchronized (spec) {
                if (spec.armedIn.size() > before) {
                    LOG.info("Breakpoint {} armed in newly loaded {}", spec.id, event.referenceType().name());
                }
            }
        }
    }

    /** Decides on the event thread whether to capture; returns the capture work, or null to skip the event. */
    private CaptureTask prepare(LocatableEvent event) {
        ThreadReference thread = event.thread();
        if (invokingThreads.contains(thread.uniqueID())) {
            return null;
        }
        Object id = event.request().getProperty(SPEC_KEY);
        if (id == null) {
            return null;
        }
        String threadName = safe(thread::name);
        RequestKey key = RequestKey.parse(threadName);
        if (event instanceof BreakpointEvent) {
            BreakpointSpec spec = breakpoints.get(String.valueOf(id));
            if (spec == null || !admit(spec, key)) {
                return null;
            }
            BreakpointEvent breakpointEvent = (BreakpointEvent) event;
            return state -> captureBreakpoint(breakpointEvent, spec, threadName, key, state);
        }
        ExceptionWatchSpec spec = watches.get(String.valueOf(id));
        if (spec == null || !admit(spec, key)) {
            return null;
        }
        ExceptionEvent exceptionEvent = (ExceptionEvent) event;
        if (!"java.lang.Throwable".equals(spec.exceptionClass)
                && !ValueRenderer.isSubtypeOf(exceptionEvent.exception().referenceType(), spec.exceptionClass)) {
            synchronized (spec) {
                spec.otherExceptions++;
            }
            return null;
        }
        return state -> captureException(exceptionEvent, spec, threadName, key, state);
    }

    /** Runs the captures of one event set in order while its thread stays suspended. */
    private static void runAll(List<CaptureTask> tasks, CaptureState state) {
        for (CaptureTask task : tasks) {
            if (!state.pendingInvokes.isEmpty()) {
                LOG.info("Skipping {} capture(s): a timed-out getter is still running on the thread",
                        tasks.size() - tasks.indexOf(task));
                return;
            }
            try {
                task.run(state);
            } catch (VMDisconnectedException e) {
                throw e;
            } catch (Exception e) {
                LOG.info("Snapshot capture failed: {}", e.toString());
            }
        }
    }

    /** Work done while the event thread stays suspended. */
    private interface CaptureTask {
        void run(CaptureState state) throws Exception;
    }

    /** Per-capture state: pending (timed out) invocations and notes. */
    static final class CaptureState {
        final List<Future<Value>> pendingInvokes = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
    }

    private boolean submit(ExecutorService executor, EventSet set, CaptureTask task) {
        try {
            executor.submit(() -> {
                CaptureState state = new CaptureState();
                try {
                    task.run(state);
                } catch (VMDisconnectedException e) {
                    LOG.debug("VM disconnected during capture");
                } catch (Exception e) {
                    LOG.info("Snapshot capture failed: {}", e.toString());
                } finally {
                    resumeAfter(set, state);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void resumeAfter(EventSet set, CaptureState state) {
        if (state.pendingInvokes.isEmpty()) {
            resumeQuietly(set);
            return;
        }
        List<Future<Value>> pending = new ArrayList<>(state.pendingInvokes);
        ExecutorService executor = invoker;
        try {
            if (executor == null) {
                throw new RejectedExecutionException("no invoker");
            }
            executor.submit(() -> {
                for (Future<Value> future : pending) {
                    try {
                        future.get(5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        LOG.debug("pending invocation ended: {}", e.toString());
                    }
                }
                resumeQuietly(set);
            });
        } catch (RejectedExecutionException e) {
            resumeQuietly(set);
        }
    }

    private static void resumeQuietly(EventSet set) {
        try {
            set.resume();
        } catch (RuntimeException e) {
            LOG.debug("resume failed: {}", e.toString());
        }
    }

    private boolean admit(BreakpointSpec spec, RequestKey key) {
        synchronized (spec) {
            if (spec.disabledReason != null) {
                disableRequests(spec.requests);
                return false;
            }
            if (!requestMatches(spec.requestFilter, key)) {
                spec.filteredOut++;
                return false;
            }
            long now = System.currentTimeMillis();
            if (now - spec.windowStart > 1000) {
                spec.windowStart = now;
                spec.windowEvents = 0;
            }
            spec.windowEvents++;
            int limit = spec.compiledCondition != null ? Math.max(spec.maxPerSecond, 500) : spec.maxPerSecond;
            if (spec.windowEvents > limit) {
                disable(spec, "flood protection: more than " + limit + " hits per second");
                return false;
            }
            if (spec.hits >= spec.maxHits) {
                disable(spec, "reached maxHits=" + spec.maxHits);
                return false;
            }
            return true;
        }
    }

    private boolean admit(ExceptionWatchSpec spec, RequestKey key) {
        synchronized (spec) {
            if (spec.disabledReason != null) {
                disableRequests(spec.requests);
                return false;
            }
            if (!requestMatches(spec.requestFilter, key)) {
                spec.filteredOut++;
                return false;
            }
            long now = System.currentTimeMillis();
            if (now - spec.windowStart > 1000) {
                spec.windowStart = now;
                spec.windowEvents = 0;
            }
            spec.windowEvents++;
            if (spec.windowEvents > spec.maxPerSecond) {
                spec.disabledReason = "flood protection: more than " + spec.maxPerSecond + " exceptions per second";
                disableRequests(spec.requests);
                notices.add(Text.formatTime(now) + " " + spec.id + " disabled: " + spec.disabledReason);
                return false;
            }
            if (spec.hits >= spec.maxHits) {
                spec.disabledReason = "reached maxHits=" + spec.maxHits;
                disableRequests(spec.requests);
                notices.add(Text.formatTime(now) + " " + spec.id + " disabled: " + spec.disabledReason);
                return false;
            }
            return true;
        }
    }

    /** null/"*" = any thread; "requests" = any Sling request; otherwise substring (or ^regex) of the request URI. */
    static boolean requestMatches(String filter, RequestKey key) {
        if (filter == null || filter.isBlank() || filter.equals("*")) {
            return true;
        }
        if (key == null) {
            return false;
        }
        if (filter.equals("requests")) {
            return true;
        }
        if (filter.startsWith("^")) {
            try {
                return java.util.regex.Pattern.compile(filter).matcher(key.uri()).find();
            } catch (RuntimeException e) {
                return false;
            }
        }
        return key.uri().contains(filter);
    }

    private void disable(BreakpointSpec spec, String reason) {
        spec.disabledReason = reason;
        disableRequests(spec.requests);
        notices.add(Text.formatTime(System.currentTimeMillis()) + " " + spec.id + " disabled: " + reason);
    }

    private static void disableRequests(List<EventRequest> requests) {
        for (EventRequest request : requests) {
            try {
                request.disable();
            } catch (RuntimeException e) {
                LOG.debug("disable failed: {}", e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ capture

    /** Raw values read from the suspended thread before any method is invoked (invocations invalidate frames). */
    static final class RawFrame {
        String location;
        final Map<String, Value> locals = new LinkedHashMap<>();
        ObjectReference self;
        String note;
    }

    static final class Raw {
        final List<String> stack = new ArrayList<>();
        final List<RawFrame> frames = new ArrayList<>();
    }

    static Raw collect(ThreadReference thread, int maxFrames, int localsFrames) throws IncompatibleThreadStateException {
        Raw raw = new Raw();
        int count = Math.min(thread.frameCount(), Math.max(1, maxFrames));
        List<StackFrame> frames = thread.frames(0, count);
        for (int i = 0; i < frames.size(); i++) {
            StackFrame frame = frames.get(i);
            String location = format(frame.location());
            raw.stack.add(location);
            if (i >= localsFrames) {
                continue;
            }
            RawFrame rawFrame = new RawFrame();
            rawFrame.location = location;
            try {
                List<LocalVariable> variables = frame.visibleVariables();
                Map<LocalVariable, Value> values = frame.getValues(variables);
                for (LocalVariable variable : variables) {
                    rawFrame.locals.put(variable.name(), values.get(variable));
                }
            } catch (AbsentInformationException e) {
                try {
                    List<Value> arguments = frame.getArgumentValues();
                    for (int a = 0; a < arguments.size(); a++) {
                        rawFrame.locals.put("arg" + a, arguments.get(a));
                    }
                } catch (RuntimeException ignored) {
                    // no arguments available
                }
                rawFrame.note = "no local variable table (compiled without -g); showing arguments only";
            }
            try {
                rawFrame.self = frame.thisObject();
            } catch (RuntimeException e) {
                rawFrame.self = null;
            }
            raw.frames.add(rawFrame);
        }
        return raw;
    }

    static String format(Location location) {
        String source;
        try {
            source = location.sourceName();
        } catch (AbsentInformationException e) {
            source = "Unknown Source";
        }
        return location.declaringType().name() + "." + location.method().name() + "(" + source + ":"
                + location.lineNumber() + ")";
    }

    private ValueRenderer renderer(ThreadReference thread, CaptureState state) {
        return new ValueRenderer(thread, (target, t, method) -> invokeWithTimeout(target, t, method, state), this::pin,
                new ValueRenderer.Limits(), state.notes);
    }

    private Value invokeWithTimeout(ObjectReference target, ThreadReference thread, Method method, CaptureState state)
            throws Exception {
        ExecutorService executor = invoker;
        if (executor == null) {
            throw new IllegalStateException("not attached");
        }
        long threadId = thread.uniqueID();
        invokingThreads.add(threadId);
        Future<Value> future;
        try {
            future = executor.submit(() -> {
                try {
                    return target.invokeMethod(thread, method, Collections.<Value>emptyList(),
                            ObjectReference.INVOKE_SINGLE_THREADED);
                } finally {
                    invokingThreads.remove(threadId);
                }
            });
        } catch (RejectedExecutionException e) {
            invokingThreads.remove(threadId);
            throw e;
        }
        try {
            return future.get(INVOKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            state.pendingInvokes.add(future);
            throw new ValueRenderer.InvokeTimeoutException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private void captureBreakpoint(BreakpointEvent event, BreakpointSpec spec, String threadName, RequestKey key,
                                   CaptureState state) throws Exception {
        ThreadReference thread = event.thread();
        Raw raw = collect(thread, spec.isLogpoint() ? 1 : spec.frames, Math.max(1, spec.localsFrames));
        ValueRenderer renderer = renderer(thread, state);
        Map<String, Object> vars = null;
        if (spec.compiledCondition != null || spec.isLogpoint()) {
            vars = simpleVars(raw, renderer);
            if (spec.compiledCondition != null) {
                boolean pass;
                try {
                    pass = spec.compiledCondition.test(vars);
                } catch (RuntimeException e) {
                    pass = false;
                    synchronized (spec) {
                        spec.lastError = "condition failed: " + e.getMessage();
                    }
                }
                if (!pass) {
                    synchronized (spec) {
                        spec.conditionMisses++;
                    }
                    return;
                }
            }
        }
        synchronized (spec) {
            if (spec.hits >= spec.maxHits) {
                return;
            }
            spec.hits++;
        }
        Snapshot snapshot = base(spec.isLogpoint() ? Snapshot.Kind.LOGPOINT : Snapshot.Kind.BREAKPOINT, spec.id,
                threadName, key, event.location(), raw);
        if (spec.isLogpoint()) {
            snapshot.message = Condition.format(spec.logMessage, vars);
        } else {
            fillFrames(snapshot, raw, renderer, spec.depth);
        }
        snapshot.notes.addAll(state.notes);
        snapshots.add(snapshot);
        synchronized (spec) {
            if (spec.hits >= spec.maxHits && spec.disabledReason == null) {
                disable(spec, "reached maxHits=" + spec.maxHits);
            }
        }
    }

    private void captureException(ExceptionEvent event, ExceptionWatchSpec spec, String threadName, RequestKey key,
                                  CaptureState state) throws Exception {
        synchronized (spec) {
            if (spec.hits >= spec.maxHits) {
                return;
            }
            spec.hits++;
        }
        ThreadReference thread = event.thread();
        Raw raw = collect(thread, spec.frames, Math.max(1, spec.localsFrames));
        ValueRenderer renderer = renderer(thread, state);
        ObjectReference exception = event.exception();
        Snapshot snapshot = base(Snapshot.Kind.EXCEPTION, spec.id, threadName, key, event.location(), raw);
        snapshot.exceptionClass = exception.referenceType().name();
        snapshot.exceptionMessage = message(exception);
        snapshot.catchLocation = event.catchLocation() == null ? null : format(event.catchLocation());
        ObjectReference current = exception;
        for (int i = 0; i < 6 && causeField != null; i++) {
            Value cause = current.getValue(causeField);
            if (!(cause instanceof ObjectReference) || ((ObjectReference) cause).uniqueID() == current.uniqueID()) {
                break;
            }
            current = (ObjectReference) cause;
            String causeMessage = message(current);
            snapshot.causes.add(current.referenceType().name() + (causeMessage == null ? "" : ": " + causeMessage));
        }
        for (Map.Entry<String, String> field : renderer.fields(exception, 2).entrySet()) {
            String name = field.getKey();
            if (!name.equals("detailMessage") && !name.equals("cause") && !name.equals("suppressedExceptions")
                    && !name.equals("...")) {
                snapshot.exceptionFields.put(name, field.getValue());
            } else if (name.equals("suppressedExceptions") && !field.getValue().contains("(0)")
                    && !field.getValue().startsWith("List(0)") && !field.getValue().contains("EmptyList")) {
                snapshot.exceptionFields.put(name, field.getValue());
            }
        }
        fillFrames(snapshot, raw, renderer, spec.depth);
        snapshot.notes.addAll(state.notes);
        snapshots.add(snapshot);
    }

    private String message(ObjectReference throwable) {
        if (detailMessageField == null) {
            return null;
        }
        try {
            Value value = throwable.getValue(detailMessageField);
            return value instanceof StringReference ? ((StringReference) value).value() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Snapshot base(Snapshot.Kind kind, String sourceId, String threadName, RequestKey key,
                                 Location location, Raw raw) {
        Snapshot snapshot = new Snapshot();
        snapshot.kind = kind;
        snapshot.sourceId = sourceId;
        snapshot.time = System.currentTimeMillis();
        snapshot.threadName = threadName;
        snapshot.requestKey = key;
        snapshot.location = format(location);
        snapshot.className = location.declaringType().name();
        snapshot.methodName = location.method().name();
        snapshot.line = location.lineNumber();
        try {
            snapshot.sourceName = location.sourceName();
        } catch (AbsentInformationException e) {
            snapshot.sourceName = null;
        }
        snapshot.stack.addAll(raw.stack);
        return snapshot;
    }

    private void fillFrames(Snapshot snapshot, Raw raw, ValueRenderer renderer, int depth) {
        for (RawFrame rawFrame : raw.frames) {
            Snapshot.Frame frame = new Snapshot.Frame();
            frame.location = rawFrame.location;
            frame.note = rawFrame.note;
            for (Map.Entry<String, Value> local : rawFrame.locals.entrySet()) {
                frame.locals.put(local.getKey(), renderer.renderNamed(local.getKey(), local.getValue(), depth));
            }
            if (rawFrame.self != null) {
                try {
                    frame.thisType = rawFrame.self.referenceType().name();
                    frame.thisId = rawFrame.self.uniqueID();
                    pin(rawFrame.self);
                    frame.thisFields.putAll(renderer.fields(rawFrame.self, Math.max(0, depth - 1)));
                } catch (RuntimeException e) {
                    frame.note = "this not readable: " + e.getClass().getSimpleName();
                }
            }
            snapshot.frames.add(frame);
        }
    }

    /** Simple values (locals first, then 'this' fields) for conditions and logpoint messages. */
    private static Map<String, Object> simpleVars(Raw raw, ValueRenderer renderer) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (raw.frames.isEmpty()) {
            return vars;
        }
        RawFrame top = raw.frames.get(0);
        for (Map.Entry<String, Value> local : top.locals.entrySet()) {
            vars.put(local.getKey(), renderer.simple(local.getValue()));
        }
        if (top.self != null) {
            Map<String, Object> self = new LinkedHashMap<>();
            try {
                List<Field> fields = new ArrayList<>();
                for (Field field : top.self.referenceType().allFields()) {
                    if (!field.isStatic()) {
                        fields.add(field);
                    }
                }
                Map<Field, Value> values = top.self.getValues(fields);
                int count = 0;
                for (Field field : fields) {
                    if (count++ > 60) {
                        break;
                    }
                    self.putIfAbsent(field.name(), renderer.simple(values.get(field)));
                }
            } catch (RuntimeException e) {
                LOG.debug("reading this failed: {}", e.toString());
            }
            vars.put("this", self);
        }
        return vars;
    }

    // ------------------------------------------------------------------ pinning

    private void pin(ObjectReference object) {
        synchronized (pinned) {
            long id = object.uniqueID();
            if (pinned.containsKey(id)) {
                return;
            }
            try {
                object.disableCollection();
            } catch (RuntimeException e) {
                return;
            }
            pinned.put(id, object);
            if (pinned.size() > MAX_PINNED) {
                Iterator<Map.Entry<Long, ObjectReference>> it = pinned.entrySet().iterator();
                Map.Entry<Long, ObjectReference> oldest = it.next();
                it.remove();
                try {
                    oldest.getValue().enableCollection();
                } catch (RuntimeException e) {
                    LOG.debug("unpin failed: {}", e.toString());
                }
            }
        }
    }

    private void unpinAll() {
        synchronized (pinned) {
            for (ObjectReference object : pinned.values()) {
                try {
                    object.enableCollection();
                } catch (RuntimeException e) {
                    LOG.debug("unpin failed: {}", e.toString());
                }
            }
            pinned.clear();
        }
    }

    /** Current fields of a pinned object (no code is invoked). */
    public String expand(long objectId, int depth) {
        ObjectReference object;
        synchronized (pinned) {
            object = pinned.get(objectId);
        }
        if (object == null || vm == null) {
            throw new ToolException("#obj" + objectId + " is not available (not captured, unpinned, or detached since)");
        }
        List<String> notes = new ArrayList<>();
        ValueRenderer renderer = new ValueRenderer(null, null, null, new ValueRenderer.Limits(), notes);
        StringBuilder sb = new StringBuilder("#obj").append(objectId).append(' ')
                .append(object.referenceType().name()).append(" (current state, read without invoking code)\n");
        if (object instanceof com.sun.jdi.ArrayReference) {
            sb.append("  ").append(renderer.render(object, depth)).append('\n');
        } else {
            for (Map.Entry<String, String> field : renderer.fields(object, depth).entrySet()) {
                sb.append("  ").append(field.getKey()).append(" = ").append(field.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ breakpoints

    public BreakpointSpec addBreakpoint(BreakpointSpec spec) {
        if (spec.condition != null && !spec.condition.isBlank()) {
            spec.compiledCondition = Condition.parse(spec.condition);
        }
        spec.id = "bp" + breakpointCounter.incrementAndGet();
        breakpoints.put(spec.id, spec);
        VirtualMachine machine = vm;
        if (machine != null) {
            arm(machine, spec);
        }
        return spec;
    }

    public BreakpointSpec breakpoint(String id) {
        return breakpoints.get(id);
    }

    public boolean removeBreakpoint(String id) {
        BreakpointSpec spec = breakpoints.remove(id);
        if (spec == null) {
            return false;
        }
        disarm(spec);
        return true;
    }

    public List<BreakpointSpec> breakpointList() {
        List<BreakpointSpec> list = new ArrayList<>(breakpoints.values());
        list.sort(Comparator.comparingInt(s -> number(s.id)));
        return list;
    }

    private static int number(String id) {
        try {
            return Integer.parseInt(id.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void disarm(BreakpointSpec spec) {
        VirtualMachine machine = vm;
        synchronized (spec) {
            if (machine != null) {
                try {
                    EventRequestManager erm = machine.eventRequestManager();
                    erm.deleteEventRequests(new ArrayList<>(spec.requests));
                    erm.deleteEventRequests(new ArrayList<>(spec.prepareRequests));
                } catch (RuntimeException e) {
                    LOG.debug("disarm failed: {}", e.toString());
                }
            }
            spec.requests.clear();
            spec.prepareRequests.clear();
            spec.armedIn.clear();
        }
    }

    private void arm(VirtualMachine machine, BreakpointSpec spec) {
        disarm(spec);
        EventRequestManager erm = machine.eventRequestManager();
        try {
            ClassPrepareRequest prepare = erm.createClassPrepareRequest();
            if (spec.hasPackage()) {
                String outer = spec.className.contains("$") ? spec.className.substring(0, spec.className.indexOf('$'))
                        : spec.className;
                prepare.addClassFilter(outer + "*");
            } else {
                prepare.addSourceNameFilter(spec.fileName != null ? spec.fileName : spec.simpleClassName() + ".java");
            }
            prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            prepare.putProperty(SPEC_KEY, spec.id);
            prepare.enable();
            synchronized (spec) {
                spec.prepareRequests.add(prepare);
            }
        } catch (RuntimeException e) {
            synchronized (spec) {
                spec.lastError = "cannot watch class loading: " + e.getMessage();
            }
        }
        List<ReferenceType> types = loadedTypes(machine, spec);
        for (ReferenceType type : types) {
            armIn(machine, spec, type);
        }
        synchronized (spec) {
            if (spec.armedIn.isEmpty() && spec.lastError == null && !types.isEmpty()) {
                spec.lastError = noCodeMessage(types, spec);
            }
        }
    }

    private List<ReferenceType> loadedTypes(VirtualMachine machine, BreakpointSpec spec) {
        List<ReferenceType> result = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (spec.hasPackage()) {
            names.add(spec.className.contains("$") ? spec.className.substring(0, spec.className.indexOf('$'))
                    : spec.className);
        }
        names.addAll(spec.candidateClasses);
        for (String name : names) {
            for (ReferenceType type : machine.classesByName(name)) {
                addWithNested(type, result);
            }
        }
        if (result.isEmpty() && !spec.hasPackage() && spec.candidateClasses.isEmpty()) {
            String simple = spec.simpleClassName();
            for (ReferenceType type : machine.allClasses()) {
                String name = type.name();
                if (name.equals(simple) || name.endsWith("." + simple)) {
                    addWithNested(type, result);
                }
            }
        }
        return result;
    }

    private static void addWithNested(ReferenceType type, List<ReferenceType> out) {
        if (out.contains(type)) {
            return;
        }
        out.add(type);
        try {
            for (ReferenceType nested : type.nestedTypes()) {
                addWithNested(nested, out);
            }
        } catch (RuntimeException e) {
            LOG.debug("nested types of {} not available", type.name());
        }
    }

    private void armIn(VirtualMachine machine, BreakpointSpec spec, ReferenceType type) {
        synchronized (spec) {
            if (spec.disabledReason != null) {
                return;
            }
            String name = type.name();
            String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            if (spec.hasPackage()) {
                String wanted = spec.className.contains("$") ? spec.className : outer;
                if (!(spec.className.contains("$") ? name.equals(wanted) || name.startsWith(wanted + "$")
                        : outer.equals(spec.className))) {
                    return;
                }
            } else if (!spec.candidateClasses.isEmpty() && !spec.candidateClasses.contains(outer)) {
                return;
            } else if (spec.candidateClasses.isEmpty()) {
                String simple = spec.simpleClassName();
                String outerSimple = outer.substring(outer.lastIndexOf('.') + 1);
                if (!outerSimple.equals(simple)) {
                    return;
                }
            }
            List<Location> locations = new ArrayList<>();
            try {
                if (spec.kind == BreakpointSpec.Kind.METHOD) {
                    for (Method method : type.methodsByName(spec.methodName)) {
                        if (!method.isAbstract() && !method.isNative() && method.declaringType().equals(type)
                                && method.location() != null) {
                            locations.add(method.location());
                        }
                    }
                } else {
                    locations.addAll(type.locationsOfLine(spec.line));
                }
            } catch (AbsentInformationException e) {
                spec.lastError = name + " has no line number information (compiled without -g)";
                return;
            } catch (RuntimeException e) {
                spec.lastError = "cannot inspect " + name + ": " + e.getMessage();
                return;
            }
            if (locations.isEmpty()) {
                return;
            }
            EventRequestManager erm = machine.eventRequestManager();
            BundleInfoResolver.LoaderInfo loader = BundleInfoResolver.resolve(type.classLoader());
            for (Location location : locations) {
                String description = format(location) + " [" + loader.describe() + "]";
                if (spec.armedIn.contains(description)) {
                    continue;
                }
                try {
                    BreakpointRequest request = erm.createBreakpointRequest(location);
                    request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    request.putProperty(SPEC_KEY, spec.id);
                    request.enable();
                    spec.requests.add(request);
                    spec.armedIn.add(description);
                } catch (RuntimeException e) {
                    spec.lastError = "cannot set breakpoint at " + description + ": " + e.getMessage();
                }
            }
            if (!spec.armedIn.isEmpty()) {
                spec.lastError = null;
            }
        }
    }

    private static String noCodeMessage(List<ReferenceType> types, BreakpointSpec spec) {
        if (spec.kind == BreakpointSpec.Kind.METHOD) {
            return "no method '" + spec.methodName + "' with code in " + types.get(0).name();
        }
        TreeSet<Integer> lines = new TreeSet<>();
        for (ReferenceType type : types) {
            try {
                for (Location location : type.allLineLocations()) {
                    lines.add(location.lineNumber());
                }
            } catch (AbsentInformationException | RuntimeException e) {
                LOG.debug("no line info for {}", type.name());
            }
        }
        Integer before = lines.floor(spec.line);
        Integer after = lines.ceiling(spec.line);
        return "line " + spec.line + " has no executable code in " + types.get(0).name()
                + (lines.isEmpty() ? "" : " (nearest lines with code: " + (before == null ? "-" : before) + ", "
                + (after == null ? "-" : after) + "; class spans " + lines.first() + "-" + lines.last() + ")")
                + ". The deployed class may be older than your source - check jvm_classes.";
    }

    // ------------------------------------------------------------------ exception watches

    public ExceptionWatchSpec addExceptionWatch(ExceptionWatchSpec spec) {
        spec.id = "ex" + watchCounter.incrementAndGet();
        watches.put(spec.id, spec);
        VirtualMachine machine = vm;
        if (machine != null) {
            armWatch(machine, spec);
        }
        return spec;
    }

    public boolean removeExceptionWatch(String id) {
        ExceptionWatchSpec spec = watches.remove(id);
        if (spec == null) {
            return false;
        }
        VirtualMachine machine = vm;
        synchronized (spec) {
            if (machine != null) {
                try {
                    machine.eventRequestManager().deleteEventRequests(new ArrayList<>(spec.requests));
                } catch (RuntimeException e) {
                    LOG.debug("removing watch failed: {}", e.toString());
                }
            }
            spec.requests.clear();
        }
        return true;
    }

    public List<ExceptionWatchSpec> watchList() {
        List<ExceptionWatchSpec> list = new ArrayList<>(watches.values());
        list.sort(Comparator.comparingInt(s -> number(s.id)));
        return list;
    }

    private void armWatch(VirtualMachine machine, ExceptionWatchSpec spec) {
        EventRequestManager erm = machine.eventRequestManager();
        synchronized (spec) {
            try {
                erm.deleteEventRequests(new ArrayList<>(spec.requests));
            } catch (RuntimeException e) {
                LOG.debug("cleanup of watch requests failed: {}", e.toString());
            }
            spec.requests.clear();
            List<String> patterns = spec.packages.isEmpty() ? Collections.singletonList(null) : spec.packages;
            for (String pattern : patterns) {
                try {
                    ExceptionRequest request = erm.createExceptionRequest(null, spec.caught, spec.uncaught);
                    if (pattern != null) {
                        request.addClassFilter(pattern);
                    }
                    request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    request.putProperty(SPEC_KEY, spec.id);
                    request.enable();
                    spec.requests.add(request);
                } catch (RuntimeException e) {
                    spec.lastError = "cannot watch " + pattern + ": " + e.getMessage();
                }
            }
        }
    }

    /** Removes breakpoints/watches created by debug_page or debug_capture (temporary=true). */
    public int removeTemporary() {
        int removed = 0;
        for (BreakpointSpec spec : breakpointList()) {
            if (spec.temporary && removeBreakpoint(spec.id)) {
                removed++;
            }
        }
        for (ExceptionWatchSpec spec : watchList()) {
            if (spec.temporary && removeExceptionWatch(spec.id)) {
                removed++;
            }
        }
        return removed;
    }

    public String removeAll() {
        int bps = 0;
        int ex = 0;
        for (BreakpointSpec spec : breakpointList()) {
            if (removeBreakpoint(spec.id)) {
                bps++;
            }
        }
        for (ExceptionWatchSpec spec : watchList()) {
            if (removeExceptionWatch(spec.id)) {
                ex++;
            }
        }
        unpinAll();
        return "removed " + Text.plural(bps, "breakpoint") + " and " + ex
                + (ex == 1 ? " exception watch" : " exception watches");
    }

    // ------------------------------------------------------------------ classes

    public List<ClassVersion> classVersions(String className) {
        VirtualMachine machine = vm;
        if (machine == null) {
            throw new ToolException("Not attached. Use jvm_connection action=attach first.");
        }
        List<ReferenceType> types = new ArrayList<>();
        if (className.contains(".")) {
            types.addAll(machine.classesByName(className));
        } else {
            for (ReferenceType type : machine.allClasses()) {
                if (type.name().endsWith("." + className) || type.name().equals(className)) {
                    types.add(type);
                    if (types.size() >= 20) {
                        break;
                    }
                }
            }
        }
        List<ClassVersion> versions = new ArrayList<>();
        for (ReferenceType type : types) {
            ClassVersion version = new ClassVersion();
            version.name = type.name();
            BundleInfoResolver.LoaderInfo loader = BundleInfoResolver.resolve(type.classLoader());
            version.loader = loader.describe();
            version.bundleId = loader.bundleId;
            version.bundleSymbolicName = loader.symbolicName;
            version.revision = loader.revisionNumber();
            try {
                version.sourceName = type.sourceName();
            } catch (AbsentInformationException e) {
                version.sourceName = null;
            }
            TreeSet<Integer> all = new TreeSet<>();
            try {
                for (Method method : type.methods()) {
                    if (method.declaringType().equals(type) && !method.isAbstract() && !method.isNative()) {
                        TreeSet<Integer> lines = new TreeSet<>();
                        for (Location location : method.allLineLocations()) {
                            lines.add(location.lineNumber());
                        }
                        if (!lines.isEmpty()) {
                            version.methodLines.put(method.name() + method.signature(),
                                    new int[] {lines.first(), lines.last()});
                            all.addAll(lines);
                        }
                    }
                }
            } catch (AbsentInformationException e) {
                LOG.debug("no line info for {}", type.name());
            }
            if (!all.isEmpty()) {
                version.firstLine = all.first();
                version.lastLine = all.last();
            }
            versions.add(version);
        }
        return versions;
    }

    // ------------------------------------------------------------------ status

    public String status() {
        StringBuilder sb = new StringBuilder();
        VirtualMachine machine = vm;
        if (machine != null) {
            sb.append("Attached to ").append(safe(machine::name)).append(' ').append(safe(machine::version))
                    .append(" at ").append(endpoint).append(" since ").append(Text.formatTime(attachedAt))
                    .append("; last JDI event ").append(Text.formatTime(lastEventAt)).append('\n');
        } else {
            sb.append("Not attached.\n");
        }
        List<BreakpointSpec> bps = breakpointList();
        sb.append("Breakpoints/logpoints: ").append(bps.isEmpty() ? "none" : "").append('\n');
        for (BreakpointSpec spec : bps) {
            sb.append("- ").append(spec.describe()).append('\n');
        }
        List<ExceptionWatchSpec> ex = watchList();
        sb.append("Exception watches: ").append(ex.isEmpty() ? "none" : "").append('\n');
        for (ExceptionWatchSpec spec : ex) {
            sb.append("- ").append(spec.describe()).append('\n');
        }
        sb.append("Snapshots stored: ").append(snapshots.size());
        if (snapshots.dropped() > 0) {
            sb.append(" (").append(snapshots.dropped()).append(" older dropped)");
        }
        sb.append('\n');
        int start = Math.max(0, notices.size() - 5);
        for (int i = start; i < notices.size(); i++) {
            sb.append("notice: ").append(notices.get(i)).append('\n');
        }
        return sb.toString();
    }

    public long lastEventAt() {
        return lastEventAt;
    }

    // ------------------------------------------------------------------ cleanup participant

    @Override
    public String name() {
        return label == null ? "JVM debugging" : "JVM debugging (" + label + ")";
    }

    @Override
    public String cleanup(ToolContext context, boolean shutdown) {
        String removed = removeAll();
        String detached = detach(shutdown ? "server shutdown" : "debug_cleanup");
        return removed + "; " + detached;
    }

    @Override
    public String onIdle() {
        if (vm == null || idleMillis <= 0) {
            return null;
        }
        if (System.currentTimeMillis() - lastEventAt < idleMillis) {
            return null;
        }
        return detach("no activity for " + (idleMillis / 60_000) + " minutes");
    }

    // ------------------------------------------------------------------ helpers

    private static ThreadFactory daemon(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private interface Supplier {
        String get();
    }

    private static String safe(Supplier supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
