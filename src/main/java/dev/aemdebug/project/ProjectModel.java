package dev.aemdebug.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain data types of the project index. */
public final class ProjectModel {

    private ProjectModel() {
    }

    /** A Java source file. */
    public static final class JavaClass {
        public String fqn;
        public String simpleName;
        public Path file;
        public String module;
        public int lineCount;
    }

    /** An injected field of a Sling Model. */
    public static final class InjectedField {
        public String name;
        public String type;
        public String annotation;
        /** Explicit name="..." of the injection, e.g. jcr:title. */
        public String nameOverride;
        public boolean optional;
        public boolean hasDefault;
        public int line;

        /** The JCR property/child name the injector looks up. */
        public String lookupName() {
            return nameOverride != null ? nameOverride : name;
        }
    }

    /** A class annotated with @Model. */
    public static final class SlingModel {
        public String className;
        public Path file;
        public final List<String> adaptables = new ArrayList<>();
        public final List<String> adapters = new ArrayList<>();
        public final List<String> resourceTypes = new ArrayList<>();
        public final List<InjectedField> fields = new ArrayList<>();
        public boolean exporter;
        public boolean optionalByDefault;
        public String postConstructMethod;
        public int postConstructLine;
        public int modelAnnotationLine;
    }

    /** A component definition (cq:Component) and its scripts. */
    public static final class Component {
        public String resourceType;
        public String title;
        public String superType;
        public String group;
        public Path folder;
        public final List<Path> scripts = new ArrayList<>();
        public boolean hasDialog;
    }

    /** An HTL script with its data-sly-use objects. */
    public static final class HtlScript {
        public Path file;
        public String jcrPath;
        public String resourceType;
        /** variable -> class name or script path. */
        public final Map<String, String> useObjects = new LinkedHashMap<>();
    }

    /** A Sling servlet registration. */
    public static final class Servlet {
        public String className;
        public Path file;
        public final List<String> resourceTypes = new ArrayList<>();
        public final List<String> paths = new ArrayList<>();
        public final List<String> selectors = new ArrayList<>();
        public final List<String> extensions = new ArrayList<>();
        public final List<String> methods = new ArrayList<>();
    }

    /** An OSGi configuration file in the content package. */
    public static final class OsgiConfigFile {
        public String pid;
        public String name;
        public String runModes;
        public Path file;
        public String jcrPath;
    }

    /** A client library folder. */
    public static final class ClientLib {
        public String jcrPath;
        public final List<String> categories = new ArrayList<>();
        public boolean allowProxy;
        public Path folder;
    }

    /** A Maven module of the project. */
    public static final class Module {
        public String name;
        public Path dir;
        public String groupId;
        public String artifactId;
        public String packaging;
        public String bundleSymbolicName;
    }
}
