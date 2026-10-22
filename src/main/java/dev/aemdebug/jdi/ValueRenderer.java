package dev.aemdebug.jdi;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VoidValue;

import dev.aemdebug.util.SecretMasker;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders JDI values as compact single-line text with depth/size limits and cycle protection. JDK collections
 * are read through their fields; AEM/Sling types (Resource, ValueMap, request, Page, Node) and other maps use a
 * small whitelist of side-effect free getters, invoked single-threaded only while the thread is suspended by the
 * event. Secret-looking names are masked.
 */
final class ValueRenderer {

    /** Size limits. */
    static final class Limits {
        int maxString = 300;
        int maxFields = 25;
        int maxElements = 12;
        int maxInvokes = 60;
        int maxChars = 700;
    }

    /** Invokes a method in the target VM (implemented by the engine: timeout + nested-event protection). */
    interface Invoker {
        Value invoke(ObjectReference target, ThreadReference thread, Method method) throws Exception;
    }

    /** Keeps objects alive for later expansion. */
    interface Pinning {
        void pin(ObjectReference object);
    }

    private static final Set<String> TO_STRING_TYPES = Set.of("java.math.BigDecimal", "java.math.BigInteger",
            "java.util.UUID", "java.net.URI", "java.net.URL", "java.util.Locale", "java.util.regex.Pattern",
            "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
            "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.Duration", "java.io.File");
    private static final Set<String> BOXES = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short",
            "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.lang.Character");
    private static final Set<String> SKIPPED_FIELDS = Set.of("backtrace", "stackTrace", "depth", "serialVersionUID",
            "SUPPRESSED_SENTINEL", "UNASSIGNED_STACK", "EMPTY_THROWABLE_ARRAY");

    private final ThreadReference thread;
    private final Invoker invoker;
    private final Pinning pinning;
    private final Limits limits;
    private final List<String> notes;
    private int invokesLeft;
    private boolean invokeBroken;
    private final Set<Long> inProgress = new HashSet<>();

    ValueRenderer(ThreadReference thread, Invoker invoker, Pinning pinning, Limits limits, List<String> notes) {
        this.thread = thread;
        this.invoker = invoker;
        this.pinning = pinning;
        this.limits = limits;
        this.notes = notes;
        this.invokesLeft = limits.maxInvokes;
    }

    /** Rendering of a named value (locals, fields), masking secrets by name. */
    String renderNamed(String name, Value value, int depth) {
        if (value != null && SecretMasker.isSecretKey(name) && !(value instanceof BooleanValue)) {
            return SecretMasker.MASK;
        }
        String text = render(value, depth);
        if (value instanceof ObjectReference && !(value instanceof StringReference) && pinning != null) {
            pinning.pin((ObjectReference) value);
            text = text + " #obj" + ((ObjectReference) value).uniqueID();
        }
        return text.length() > limits.maxChars ? text.substring(0, limits.maxChars) + "...}" : text;
    }

    String render(Value value, int depth) {
        try {
            return renderValue(value, depth);
        } catch (ObjectCollectedException e) {
            return "<collected>";
        } catch (RuntimeException e) {
            return "<" + e.getClass().getSimpleName() + ">";
        }
    }

    private String renderValue(Value value, int depth) {
        if (value == null) {
            return "null";
        }
        if (value instanceof PrimitiveValue || value instanceof VoidValue) {
            return primitive(value);
        }
        if (value instanceof StringReference) {
            return quote(((StringReference) value).value());
        }
        if (value instanceof ArrayReference) {
            return array((ArrayReference) value, depth);
        }
        if (value instanceof ClassObjectReference) {
            return "class " + ((ClassObjectReference) value).reflectedType().name();
        }
        ObjectReference object = (ObjectReference) value;
        long id = object.uniqueID();
        if (inProgress.contains(id)) {
            return simpleType(object) + "@" + id + " (cycle)";
        }
        inProgress.add(id);
        try {
            return object(object, depth);
        } finally {
            inProgress.remove(id);
        }
    }

    private String object(ObjectReference object, int depth) {
        ReferenceType type = object.referenceType();
        String name = type.name();
        if (BOXES.contains(name)) {
            return primitive(field(object, "value"));
        }
        if (isSubtypeOf(type, "java.lang.Enum")) {
            Value enumName = field(object, "name");
            return simpleType(object) + "." + (enumName instanceof StringReference
                    ? ((StringReference) enumName).value() : "?");
        }
        if (name.equals("java.util.Date") || name.equals("java.sql.Timestamp")) {
            Value time = field(object, "fastTime");
            return time instanceof LongValue ? "Date(" + formatMillis(((LongValue) time).value()) + ")" : "Date";
        }
        if (isSubtypeOf(type, "java.util.Calendar")) {
            Value time = field(object, "time");
            return time instanceof LongValue ? "Calendar(" + formatMillis(((LongValue) time).value()) + ")" : "Calendar";
        }
        if (name.equals("java.util.Optional")) {
            Value inner = field(object, "value");
            return inner == null ? "Optional.empty" : "Optional[" + render(inner, depth) + "]";
        }
        if (name.startsWith("java.util.concurrent.atomic.Atomic") && field(object, "value") != null) {
            return simpleType(object) + "(" + render(field(object, "value"), depth) + ")";
        }
        if (isSubtypeOf(type, "java.lang.Throwable")) {
            Value message = field(object, "detailMessage");
            return name + (message instanceof StringReference ? ": " + quote(((StringReference) message).value()) : "");
        }
        if (TO_STRING_TYPES.contains(name)) {
            String text = invokeString(object, "toString");
            if (text != null) {
                return simpleType(object) + "(" + text + ")";
            }
        }
        String aem = aemObject(object, type, depth);
        if (aem != null) {
            return aem;
        }
        String collection = jdkCollection(object, name, depth);
        if (collection != null) {
            return collection;
        }
        if (isSubtypeOf(type, "java.util.Map")) {
            String map = invokedMap(object, depth);
            if (map != null) {
                return map;
            }
        } else if (isSubtypeOf(type, "java.util.Collection")) {
            String coll = invokedCollection(object, depth);
            if (coll != null) {
                return coll;
            }
        }
        if (depth <= 0) {
            return simpleType(object) + "@" + object.uniqueID();
        }
        StringBuilder sb = new StringBuilder(simpleType(object)).append('{');
        int count = 0;
        for (Map.Entry<String, String> entry : fields(object, depth - 1).entrySet()) {
            if (count++ > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            if (sb.length() > limits.maxChars) {
                sb.append(", ...");
                break;
            }
        }
        return sb.append('}').toString();
    }

    /** Instance fields of an object, rendered at the given depth. */
    Map<String, String> fields(ObjectReference object, int depth) {
        Map<String, String> result = new LinkedHashMap<>();
        List<Field> fields = new ArrayList<>();
        for (Field field : object.referenceType().allFields()) {
            if (!field.isStatic() && !SKIPPED_FIELDS.contains(field.name())) {
                fields.add(field);
            }
        }
        int count = 0;
        Map<Field, Value> values = object.getValues(fields);
        for (Field field : fields) {
            if (count++ >= limits.maxFields) {
                result.put("...", (fields.size() - limits.maxFields) + " more fields");
                break;
            }
            Value value = values.get(field);
            String key = result.containsKey(field.name()) ? field.declaringType().name() + "." + field.name() : field.name();
            if (SecretMasker.isSecretKey(field.name()) && value != null && !(value instanceof BooleanValue)) {
                result.put(key, SecretMasker.MASK);
            } else {
                result.put(key, render(value, depth));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ AEM / Sling types

    private String aemObject(ObjectReference object, ReferenceType type, int depth) {
        if (isSubtypeOf(type, "org.apache.sling.api.resource.Resource")) {
            String path = invokeString(object, "getPath");
            String resourceType = invokeString(object, "getResourceType");
            if (path != null || resourceType != null) {
                StringBuilder sb = new StringBuilder("Resource{path=").append(quote(path)).append(", type=")
                        .append(quote(resourceType));
                if (depth >= 2) {
                    Value valueMap = invoke(object, "getValueMap", "()Lorg/apache/sling/api/resource/ValueMap;");
                    if (valueMap instanceof ObjectReference) {
                        sb.append(", props=").append(render(valueMap, 1));
                    }
                }
                return sb.append('}').toString();
            }
        }
        if (isSubtypeOf(type, "org.apache.sling.api.SlingHttpServletRequest")) {
            String uri = invokeString(object, "getRequestURI");
            Value resource = invoke(object, "getResource", "()Lorg/apache/sling/api/resource/Resource;");
            String resourcePath = resource instanceof ObjectReference
                    ? invokeString((ObjectReference) resource, "getPath") : null;
            if (uri != null || resourcePath != null) {
                return "SlingHttpServletRequest{uri=" + quote(uri) + ", resource=" + quote(resourcePath) + "}";
            }
        }
        if (isSubtypeOf(type, "com.day.cq.wcm.api.Page")) {
            String path = invokeString(object, "getPath");
            String title = invokeString(object, "getTitle");
            if (path != null) {
                return "Page{path=" + quote(path) + ", title=" + quote(title) + "}";
            }
        }
        if (isSubtypeOf(type, "javax.jcr.Node")) {
            String path = invokeString(object, "getPath");
            if (path != null) {
                return "Node{path=" + quote(path) + "}";
            }
        }
        if (type.name().equals("org.apache.sling.api.wrappers.ValueMapDecorator")) {
            Value base = field(object, "base");
            if (base instanceof ObjectReference) {
                return "ValueMap" + stripType(render(base, depth));
            }
        }
        return null;
    }

    /** Resource summary (path/type) for conditions, or null. */
    Map<String, Object> resourceSummary(ObjectReference object) {
        if (!isSubtypeOf(object.referenceType(), "org.apache.sling.api.resource.Resource")) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("path", invokeString(object, "getPath"));
        summary.put("type", invokeString(object, "getResourceType"));
        summary.put("resourceType", summary.get("type"));
        return summary;
    }

    // ------------------------------------------------------------------ JDK collections through fields

    private String jdkCollection(ObjectReference object, String name, int depth) {
        switch (name) {
            case "java.util.ArrayList":
            case "java.util.Vector": {
                int size = intField(object, name.equals("java.util.Vector") ? "elementCount" : "size");
                return listOf(object, "elementData", size, depth);
            }
            case "java.util.Arrays$ArrayList": {
                Value array = field(object, "a");
                return array instanceof ArrayReference ? "List" + array((ArrayReference) array, depth) : null;
            }
            case "java.util.ImmutableCollections$ListN":
            case "java.util.ImmutableCollections$SetN": {
                Value array = field(object, "elements");
                if (array instanceof ArrayReference) {
                    List<Value> items = new ArrayList<>();
                    for (Value item : ((ArrayReference) array).getValues()) {
                        if (item != null) {
                            items.add(item);
                        }
                    }
                    return (name.endsWith("ListN") ? "List" : "Set") + "(" + items.size() + ")" + elements(items, depth);
                }
                return null;
            }
            case "java.util.ImmutableCollections$List12":
            case "java.util.ImmutableCollections$Set12": {
                List<Value> items = new ArrayList<>();
                Value e0 = field(object, "e0");
                Value e1 = field(object, "e1");
                if (e0 != null) {
                    items.add(e0);
                }
                if (e1 instanceof ObjectReference && !(e1 instanceof StringReference)
                        && ((ObjectReference) e1).referenceType().name().equals("java.lang.Object")) {
                    e1 = null;
                }
                if (e1 != null) {
                    items.add(e1);
                }
                return (name.contains("List") ? "List" : "Set") + "(" + items.size() + ")" + elements(items, depth);
            }
            case "java.util.ImmutableCollections$Map1": {
                Value k0 = field(object, "k0");
                if (k0 == null) {
                    return null;
                }
                List<Value> keys = new ArrayList<>();
                keys.add(k0);
                List<Value> values = new ArrayList<>();
                values.add(field(object, "v0"));
                return mapOf(keys, values, 1, depth);
            }
            case "java.util.ImmutableCollections$MapN": {
                Value table = field(object, "table");
                if (!(table instanceof ArrayReference)) {
                    return null;
                }
                List<Value> raw = ((ArrayReference) table).getValues();
                List<Value> keys = new ArrayList<>();
                List<Value> values = new ArrayList<>();
                for (int i = 0; i + 1 < raw.size(); i += 2) {
                    if (raw.get(i) != null) {
                        keys.add(raw.get(i));
                        values.add(raw.get(i + 1));
                    }
                }
                return mapOf(keys, values, keys.size(), depth);
            }
            case "java.util.HashMap":
            case "java.util.LinkedHashMap":
            case "java.util.concurrent.ConcurrentHashMap":
                return hashMap(object, depth);
            case "java.util.HashSet":
            case "java.util.LinkedHashSet": {
                Value map = field(object, "map");
                if (map instanceof ObjectReference) {
                    String keys = hashMapKeys((ObjectReference) map, depth);
                    return keys == null ? null : "Set" + keys;
                }
                return null;
            }
            case "java.util.Collections$UnmodifiableMap":
            case "java.util.Collections$SynchronizedMap":
            case "java.util.Collections$UnmodifiableSortedMap":
                return delegate(object, "m", depth);
            case "java.util.Collections$UnmodifiableRandomAccessList":
            case "java.util.Collections$UnmodifiableList":
                return delegate(object, "list", depth);
            case "java.util.Collections$UnmodifiableCollection":
            case "java.util.Collections$UnmodifiableSet":
            case "java.util.Collections$SynchronizedCollection":
                return delegate(object, "c", depth);
            case "java.util.Collections$EmptyList":
                return "List(0)[]";
            case "java.util.Collections$EmptyMap":
                return "Map(0){}";
            case "java.util.Collections$EmptySet":
                return "Set(0)[]";
            default:
                return null;
        }
    }

    private String delegate(ObjectReference object, String fieldName, int depth) {
        Value inner = field(object, fieldName);
        return inner == null ? null : render(inner, depth);
    }

    private String listOf(ObjectReference object, String arrayField, int size, int depth) {
        Value array = field(object, arrayField);
        if (!(array instanceof ArrayReference)) {
            return null;
        }
        ArrayReference ref = (ArrayReference) array;
        int count = Math.min(size, Math.min(ref.length(), limits.maxElements));
        List<Value> items = count > 0 ? ref.getValues(0, count) : new ArrayList<>();
        return "List(" + size + ")" + elements(items, depth) + (size > count ? "+" : "");
    }

    private String elements(List<Value> items, int depth) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size() && i < limits.maxElements; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(render(items.get(i), Math.max(0, depth - 1)));
            if (sb.length() > limits.maxChars) {
                sb.append(", ...");
                break;
            }
        }
        if (items.size() > limits.maxElements) {
            sb.append(", ...");
        }
        return sb.append(']').toString();
    }

    private String hashMap(ObjectReference map, int depth) {
        Value table = field(map, "table");
        int size = intField(map, "size");
        if (size < 0 && field(map, "baseCount") instanceof LongValue) {
            size = (int) ((LongValue) field(map, "baseCount")).value();
        }
        List<Value> keys = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        Value head = field(map, "head");
        if (head instanceof ObjectReference) {
            for (Value entry = head; entry instanceof ObjectReference && keys.size() < limits.maxElements;
                 entry = field((ObjectReference) entry, "after")) {
                keys.add(field((ObjectReference) entry, "key"));
                values.add(field((ObjectReference) entry, "value"));
            }
        } else if (table instanceof ArrayReference) {
            for (Value bucket : ((ArrayReference) table).getValues()) {
                for (Value node = bucket; node instanceof ObjectReference && keys.size() < limits.maxElements;
                     node = field((ObjectReference) node, "next")) {
                    ObjectReference n = (ObjectReference) node;
                    keys.add(field(n, "key"));
                    Value value = field(n, "value");
                    values.add(value != null ? value : field(n, "val"));
                }
                if (keys.size() >= limits.maxElements) {
                    break;
                }
            }
        } else if (table == null && size <= 0) {
            return "Map(0){}";
        } else {
            return null;
        }
        return mapOf(keys, values, Math.max(size, keys.size()), depth);
    }

    private String hashMapKeys(ObjectReference map, int depth) {
        String rendered = hashMap(map, depth);
        if (rendered == null) {
            return null;
        }
        return rendered.replaceAll("=[^,}]*", "").replace('{', '[').replace('}', ']').replaceFirst("^Map", "");
    }

    private String mapOf(List<Value> keys, List<Value> values, int size, int depth) {
        StringBuilder sb = new StringBuilder("Map(").append(size).append("){");
        for (int i = 0; i < keys.size() && i < limits.maxElements; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String key = keys.get(i) instanceof StringReference ? ((StringReference) keys.get(i)).value()
                    : render(keys.get(i), 0);
            sb.append(key).append('=');
            if (SecretMasker.isSecretKey(key) && values.get(i) != null) {
                sb.append(SecretMasker.MASK);
            } else {
                sb.append(render(values.get(i), Math.max(0, depth - 1)));
            }
            if (sb.length() > limits.maxChars) {
                sb.append(", ...");
                break;
            }
        }
        if (size > keys.size()) {
            sb.append(", ...");
        }
        return sb.append('}').toString();
    }

    // ------------------------------------------------------------------ invocation-based fallbacks

    private String invokedMap(ObjectReference map, int depth) {
        Value entrySet = invoke(map, "entrySet", "()Ljava/util/Set;");
        if (!(entrySet instanceof ObjectReference)) {
            return null;
        }
        Value array = invoke((ObjectReference) entrySet, "toArray", "()[Ljava/lang/Object;");
        if (!(array instanceof ArrayReference)) {
            return null;
        }
        ArrayReference entries = (ArrayReference) array;
        List<Value> keys = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        int count = Math.min(entries.length(), limits.maxElements);
        for (Value entry : count > 0 ? entries.getValues(0, count) : new ArrayList<Value>()) {
            if (!(entry instanceof ObjectReference)) {
                continue;
            }
            Value key = invoke((ObjectReference) entry, "getKey", "()Ljava/lang/Object;");
            Value value = invoke((ObjectReference) entry, "getValue", "()Ljava/lang/Object;");
            keys.add(key);
            values.add(value);
        }
        String rendered = mapOf(keys, values, entries.length(), depth);
        return simpleType(map) + rendered.substring("Map".length());
    }

    private String invokedCollection(ObjectReference collection, int depth) {
        Value array = invoke(collection, "toArray", "()[Ljava/lang/Object;");
        if (!(array instanceof ArrayReference)) {
            return null;
        }
        ArrayReference items = (ArrayReference) array;
        int count = Math.min(items.length(), limits.maxElements);
        List<Value> values = count > 0 ? items.getValues(0, count) : new ArrayList<>();
        return simpleType(collection) + "(" + items.length() + ")" + elements(values, depth);
    }

    private String invokeString(ObjectReference object, String method) {
        Value value = invoke(object, method, "()Ljava/lang/String;");
        return value instanceof StringReference ? ((StringReference) value).value() : null;
    }

    Value invoke(ObjectReference object, String methodName, String signature) {
        if (thread == null || invoker == null || invokeBroken || invokesLeft <= 0) {
            return null;
        }
        Method method = null;
        ReferenceType type = object.referenceType();
        if (type instanceof ClassType) {
            method = ((ClassType) type).concreteMethodByName(methodName, signature);
        } else {
            List<Method> methods = type.methodsByName(methodName, signature);
            method = methods.isEmpty() ? null : methods.get(0);
        }
        if (method == null) {
            return null;
        }
        invokesLeft--;
        try {
            return invoker.invoke(object, thread, method);
        } catch (InvokeTimeoutException e) {
            invokeBroken = true;
            notes.add("getter " + methodName + "() did not return in time; further getters skipped");
            return null;
        } catch (Exception e) {
            if (e.getClass().getSimpleName().equals("IncompatibleThreadStateException")) {
                invokeBroken = true;
            }
            return null;
        }
    }

    /** Signals that an invocation was abandoned after the timeout. */
    static final class InvokeTimeoutException extends Exception {
        private static final long serialVersionUID = 1L;

        InvokeTimeoutException() {
            super("invocation timed out");
        }
    }

    // ------------------------------------------------------------------ simple values for conditions

    Object simple(Value value) {
        try {
            if (value == null) {
                return null;
            }
            if (value instanceof BooleanValue) {
                return ((BooleanValue) value).value();
            }
            if (value instanceof CharValue) {
                return String.valueOf(((CharValue) value).value());
            }
            if (value instanceof com.sun.jdi.FloatValue || value instanceof com.sun.jdi.DoubleValue) {
                return ((PrimitiveValue) value).doubleValue();
            }
            if (value instanceof PrimitiveValue) {
                return ((PrimitiveValue) value).longValue();
            }
            if (value instanceof StringReference) {
                return ((StringReference) value).value();
            }
            if (value instanceof ObjectReference) {
                ObjectReference object = (ObjectReference) value;
                String name = object.referenceType().name();
                if (BOXES.contains(name)) {
                    return simple(field(object, "value"));
                }
                if (isSubtypeOf(object.referenceType(), "java.lang.Enum")) {
                    Value enumName = field(object, "name");
                    return enumName instanceof StringReference ? ((StringReference) enumName).value() : name;
                }
                Map<String, Object> resource = resourceSummary(object);
                if (resource != null) {
                    return resource;
                }
                return simpleType(object) + "@" + object.uniqueID();
            }
            return value.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private String array(ArrayReference array, int depth) {
        int length = array.length();
        String component = array.referenceType().name();
        component = component.endsWith("[]") ? component.substring(0, component.length() - 2) : component;
        int count = Math.min(length, limits.maxElements);
        List<Value> values = count > 0 ? array.getValues(0, count) : new ArrayList<>();
        StringBuilder sb = new StringBuilder(shortName(component)).append('[').append(length).append("]{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(render(values.get(i), Math.max(0, depth - 1)));
            if (sb.length() > limits.maxChars) {
                sb.append(", ...");
                break;
            }
        }
        if (length > count) {
            sb.append(", ...");
        }
        return sb.append('}').toString();
    }

    static String primitive(Value value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BooleanValue) {
            return String.valueOf(((BooleanValue) value).value());
        }
        if (value instanceof CharValue) {
            return "'" + ((CharValue) value).value() + "'";
        }
        if (value instanceof ByteValue) {
            return String.valueOf(((ByteValue) value).value());
        }
        if (value instanceof ShortValue) {
            return String.valueOf(((ShortValue) value).value());
        }
        if (value instanceof IntegerValue) {
            return String.valueOf(((IntegerValue) value).value());
        }
        if (value instanceof LongValue) {
            return String.valueOf(((LongValue) value).value());
        }
        if (value instanceof FloatValue) {
            return String.valueOf(((FloatValue) value).value());
        }
        if (value instanceof DoubleValue) {
            return String.valueOf(((DoubleValue) value).value());
        }
        if (value instanceof VoidValue) {
            return "void";
        }
        return value.toString();
    }

    private String quote(String text) {
        if (text == null) {
            return "null";
        }
        String value = text.length() > limits.maxString ? text.substring(0, limits.maxString) + "...(" + text.length()
                + " chars)" : text;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    static Value field(ObjectReference object, String name) {
        try {
            Field field = object.referenceType().fieldByName(name);
            return field == null ? null : object.getValue(field);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int intField(ObjectReference object, String name) {
        Value value = field(object, name);
        return value instanceof IntegerValue ? ((IntegerValue) value).value() : -1;
    }

    static boolean isSubtypeOf(ReferenceType type, String name) {
        if (type.name().equals(name)) {
            return true;
        }
        try {
            if (type instanceof ClassType) {
                ClassType classType = (ClassType) type;
                for (InterfaceType implemented : classType.allInterfaces()) {
                    if (implemented.name().equals(name)) {
                        return true;
                    }
                }
                for (ClassType superclass = classType.superclass(); superclass != null;
                     superclass = superclass.superclass()) {
                    if (superclass.name().equals(name)) {
                        return true;
                    }
                }
            } else if (type instanceof InterfaceType) {
                for (InterfaceType parent : ((InterfaceType) type).superinterfaces()) {
                    if (isSubtypeOf(parent, name)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private static String simpleType(ObjectReference object) {
        return shortName(object.referenceType().name());
    }

    static String shortName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static String stripType(String rendered) {
        int brace = rendered.indexOf('(');
        int curly = rendered.indexOf('{');
        int start = brace >= 0 && (curly < 0 || brace < curly) ? brace : curly;
        return start >= 0 ? rendered.substring(start) : rendered;
    }

    private static String formatMillis(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString();
    }
}
