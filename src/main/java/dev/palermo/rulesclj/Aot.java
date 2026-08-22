package dev.palermo.rulesclj;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles Clojure namespaces ahead of time, one target's worth at a time.
 *
 * <p>This class runs on the classpath of the code it is compiling, so it deliberately depends on
 * nothing but the JDK. Clojure is reached by reflection through {@code clojure.java.api.Clojure},
 * the published entry point, which means this shim neither needs Clojure to compile nor cares which
 * version it meets at runtime.
 *
 * <h2>Why not just call {@code compile}</h2>
 *
 * <p>{@code (compile 'foo.core)} is transitive: loading {@code foo.core} loads everything its
 * {@code ns} form requires, and with {@code *compile-files*} bound those namespaces are written out
 * too. In a build graph that is wrong — two targets would each contain a copy of a shared
 * namespace's classes, changing one file would invalidate every jar that happened to compile it,
 * and duplicate {@code deftype} classes across jars break {@code instance?} outright.
 *
 * <p>So compilation happens in two passes. First {@code require} the namespace with
 * {@code *compile-files*} unbound, which loads it and its dependencies from whatever their own jars
 * provide and writes nothing. Then bind {@code *compile-files*} and {@code load} the namespace
 * again: its dependencies are already in {@code *loaded-libs*}, so the {@code require} in its
 * {@code ns} form is a no-op, and only the namespace's own code is emitted.
 */
public final class Aot {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--persistent_worker")) {
            Worker.serve();
            return;
        }
        compile(Args.parse(args));
    }

    /**
     * Compiles one target's worth of namespaces.
     *
     * <p>Takes its classpath from the request rather than from the JVM it runs in, which is what
     * lets the persistent worker serve targets whose classpaths differ — see {@link Worker}.
     */
    static void compile(Args parsed) throws Exception {
        if (parsed.warmup) {
            new Clojure(parsed.classpath).warmUp();
            return;
        }

        // The classes directory is scratch, not an output: the jar is what the build
        // declares. Making it a temp dir keeps it out of the action's output tree, so a
        // stale class from a previous run cannot survive into a jar.
        Path classes =
                parsed.classesDir != null
                        ? parsed.classesDir
                        : Files.createTempDirectory("rules_clj_aot");
        deleteRecursively(classes);
        Files.createDirectories(classes);

        Clojure clojure = new Clojure(parsed.classpath);
        try {
            for (String namespace : parsed.namespaces) {
                clojure.compileNamespace(namespace, classes);
            }

            verifyOnlyRequestedNamespaces(classes, parsed.namespaces);

            Map<String, Path> entries = new LinkedHashMap<>(parsed.resources);
            for (Path classFile : walk(classes)) {
                entries.put(relative(classes, classFile), classFile);
            }
            Jars.write(parsed.output, entries);
        } finally {
            clojure.close();
        }
    }

    /** Scratch from a previous request in the same worker must not reach this one's jar. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Fails if compilation produced a namespace that was not asked for.
     *
     * <p>Clojure emits exactly one {@code __init.class} per compiled namespace, which makes the set
     * of those files an precise record of what was compiled — far better than guessing from class
     * names, since a {@code deftype} or {@code gen-class} in {@code foo.core} is named after the
     * type ({@code foo/Thing.class}) and shares no prefix with its namespace.
     *
     * <p>A leak here means the two-pass scheme above failed for this target, and the jar would
     * carry someone else's classes. Better to stop than to ship it.
     */
    private static void verifyOnlyRequestedNamespaces(Path classes, List<String> requested)
            throws IOException {
        Set<String> expected = requested.stream().collect(Collectors.toCollection(TreeSet::new));
        Set<String> found = new TreeSet<>();
        for (Path file : walk(classes)) {
            String name = relative(classes, file);
            if (name.endsWith("__init.class")) {
                found.add(demunge(name.substring(0, name.length() - "__init.class".length())));
            }
        }
        if (!found.equals(expected)) {
            Set<String> unexpected = new TreeSet<>(found);
            unexpected.removeAll(expected);
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(found);
            StringBuilder message =
                    new StringBuilder("ahead-of-time compilation produced the wrong set of namespaces.");
            if (!unexpected.isEmpty()) {
                message.append("\n  compiled but not requested: ")
                        .append(unexpected)
                        .append("\n    A dependency was compiled into this target instead of being")
                        .append(" loaded from its own jar. Two targets would then ship the same")
                        .append(" classes, which breaks protocol and deftype identity. Declare it in")
                        .append(" deps so it is loaded rather than compiled.");
            }
            if (!missing.isEmpty()) {
                message.append("\n  requested but not compiled: ")
                        .append(missing)
                        .append("\n    Nothing was emitted for these. The usual cause is that a")
                        .append(" dependency jar already contains them compiled, so `load` used the")
                        .append(" class instead of the source — meaning two targets declare the same")
                        .append(" namespace. Each namespace belongs to exactly one target.");
            }
            throw new IllegalStateException(message.toString());
        }
    }

    /** {@code foo/bar_baz} -> {@code foo.bar-baz}: the inverse of Clojure's name munging. */
    private static String demunge(String path) {
        return path.replace('/', '.').replace('_', '-');
    }

    /** {@code foo.bar-baz} -> {@code /foo/bar_baz}, which is what {@code load} wants. */
    private static String rootResource(String namespace) {
        return "/" + namespace.replace('-', '_').replace('.', '/');
    }

    private static List<Path> walk(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }

    /**
     * A Clojure runtime, loaded per request in a classloader of its own.
     *
     * <p>The isolation is not incidental. Clojure's runtime state — loaded namespaces, protocol
     * implementations, the compiler's own caches — lives in statics belonging to its classes, so a
     * runtime shared between two targets carries the first target's namespaces into the second.
     * The two-pass compile depends on knowing exactly what is loaded, and a dirty runtime silently
     * breaks it: a dependency left over from an earlier target makes the pre-require a no-op, and
     * whether a namespace gets compiled starts depending on build order.
     *
     * <p>A fresh loader per request costs the class loading the worker was supposed to avoid. What
     * the worker still saves is real but bounded: JVM startup, and a JIT that has already seen this
     * work. Sharing more than that is possible only by giving up the guarantee above, which is not
     * a trade this ruleset makes.
     */
    private static final class Clojure implements AutoCloseable {
        private final Method invoke1;
        private final Object eval;
        private final Object readString;
        private final URLClassLoader loader;
        private final ClassLoader previousContextLoader;

        Clojure(List<String> classpath) throws Exception {
            URL[] urls = new URL[classpath.size()];
            for (int i = 0; i < classpath.size(); i++) {
                urls[i] = Paths.get(classpath.get(i)).toUri().toURL();
            }

            // Parent is the platform loader, not the application loader: the shim's own classes
            // must not be visible to the code being compiled, and a Clojure on the application
            // classpath would otherwise shadow the one the request asked for.
            this.loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            this.previousContextLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);

            Class<?> api = Class.forName("clojure.java.api.Clojure", true, loader);
            Method var = api.getMethod("var", Object.class, Object.class);
            this.invoke1 = Class.forName("clojure.lang.IFn", true, loader).getMethod("invoke", Object.class);
            this.eval = var.invoke(null, "clojure.core", "eval");
            this.readString = var.invoke(null, "clojure.core", "read-string");
        }

        @Override
        public void close() throws IOException {
            Thread.currentThread().setContextClassLoader(previousContextLoader);
            loader.close();
        }

        /**
         * Runs the two-pass compile for one namespace.
         *
         * <p>Expressed as a Clojure form rather than a sequence of reflective calls because the
         * work is mostly {@code binding}, and driving thread-local var bindings through reflection
         * is a great deal of ceremony for no gain. The form is built here rather than shipped as a
         * {@code .clj} resource so that this shim adds no namespace to the user's classpath.
         */
        void compileNamespace(String namespace, Path classes) throws Exception {
            String form =
                    "(binding [*compile-path* "
                            + literal(classes.toString())
                            + "]"
                            + "  (require (quote "
                            + namespace
                            + "))"
                            + "  (binding [*compile-files* true]"
                            + "    (load "
                            + literal(rootResource(namespace))
                            + ")))";
            try {
                invoke1.invoke(eval, invoke1.invoke(readString, form));
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException("compiling " + namespace + ": " + cause, cause);
            }
        }

        /**
         * Exercises the compiler without compiling a file.
         *
         * <p>Used only when dumping a CDS archive. It takes no classpath entry of its own,
         * which is the whole point: an archive is only used when the runtime classpath
         * begins with the dump-time classpath, so the dump must see the Clojure runtime and
         * the shim and nothing else. Loading a training namespace from a directory would add
         * a fourth entry and quietly invalidate every archive lookup afterwards.
         *
         * <p>The forms are chosen to reach the code paths a real compile takes: eval invokes
         * the compiler, and protocols, records and types pull in the machinery that generates
         * classes.
         */
        void warmUp() throws Exception {
            // Definitions land in whatever namespace is current — clojure.core, as it
            // happens, because nothing here runs clojure.main to set up `user`. Switching
            // first was tried and does not work: under eval, *ns* has no thread binding, so
            // in-ns cannot move it ("Can't change/establish root binding of: *ns*"). It does
            // not matter. This JVM exists to dump an archive and then exit; the names are
            // prefixed only so that anyone reading a stack trace knows where they came from.
            //
            // The forms are chosen to reach what a real compile reaches: eval invokes the
            // compiler, and protocols, records and types pull in the class-generating
            // machinery that dominates a first compile.
            String form =
                    "(do"
                        + " (require (quote clojure.string) (quote clojure.set)"
                        + "          (quote clojure.walk) (quote clojure.java.io))"
                        + " (defprotocol RulesCljWarmed (rules-clj-warm [this]))"
                        + " (defrecord RulesCljRecord [x] RulesCljWarmed (rules-clj-warm [_] x))"
                        + " (deftype RulesCljType [x] clojure.lang.IDeref (deref [_] x))"
                        + " (defmacro rules-clj-macro [e] (list 'identity e))"
                        + " (defn rules-clj-fn [xs] (mapv inc (sort (set xs))))"
                        + " (rules-clj-fn [3 1 2])"
                        + " (rules-clj-warm (->RulesCljRecord 1))"
                        + " (deref (->RulesCljType 2)))";
            invoke1.invoke(eval, invoke1.invoke(readString, form));
        }

        private static String literal(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /** Flags, with {@code @file} expansion because a classpath's worth of them exceeds ARG_MAX. */
    static final class Args {
        Path output;
        Path classesDir;
        boolean warmup;
        final List<String> classpath = new ArrayList<>();
        final List<String> namespaces = new ArrayList<>();
        final Map<String, Path> resources = new LinkedHashMap<>();

        static Args parse(String[] argv) throws IOException {
            Args args = new Args();
            for (String arg : expand(argv)) {
                int eq = arg.indexOf('=');
                if (!arg.startsWith("--") || eq < 0) {
                    throw new IllegalArgumentException("unrecognised argument: " + arg);
                }
                String flag = arg.substring(2, eq);
                String value = arg.substring(eq + 1);
                switch (flag) {
                    case "output" -> args.output = Paths.get(value);
                    case "classes-dir" -> args.classesDir = Paths.get(value);
                    case "namespace" -> args.namespaces.add(value);
                    case "classpath" -> {
                        for (String entry : value.split(java.io.File.pathSeparator)) {
                            if (!entry.isEmpty()) {
                                args.classpath.add(entry);
                            }
                        }
                    }
                    case "warmup" -> args.warmup = Boolean.parseBoolean(value);
                    case "resource" -> {
                        // entry-in-jar=path-on-disk; the path may itself contain '='.
                        int split = value.indexOf('=');
                        if (split < 0) {
                            throw new IllegalArgumentException("--resource needs entry=path: " + value);
                        }
                        args.resources.put(value.substring(0, split), Paths.get(value.substring(split + 1)));
                    }
                    default -> throw new IllegalArgumentException("unrecognised flag: --" + flag);
                }
            }
            if (args.classpath.isEmpty()) {
                throw new IllegalArgumentException("--classpath is required");
            }
            if (args.warmup) {
                return args;
            }
            if (args.output == null) {
                throw new IllegalArgumentException("--output is required");
            }
            if (args.namespaces.isEmpty()) {
                throw new IllegalArgumentException("at least one --namespace is required");
            }
            return args;
        }

        private static List<String> expand(String[] argv) throws IOException {
            List<String> out = new ArrayList<>();
            for (String arg : argv) {
                if (arg.startsWith("@")) {
                    out.addAll(Files.readAllLines(Paths.get(arg.substring(1)), StandardCharsets.UTF_8));
                } else {
                    out.add(arg);
                }
            }
            return out.stream().filter(s -> !s.isEmpty()).collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private Aot() {}
}
