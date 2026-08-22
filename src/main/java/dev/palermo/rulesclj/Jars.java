package dev.palermo.rulesclj;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Writes jars that are byte-identical across builds of identical inputs.
 *
 * <p>Two properties matter and neither is the default. Entries are written in sorted order, because
 * a filesystem walk is not ordered and an unordered zip is a different zip. And timestamps are
 * fixed, because the current time is the one input a build cannot control.
 *
 * <p>The fixed timestamps are not arbitrary. Clojure's loader compares the modification time of
 * {@code foo/bar.clj} against {@code foo/bar.class} and takes the source if it looks newer, falling
 * back to compiling at runtime. Both files can end up in one jar, so the class must be stamped
 * later than the source or the ahead-of-time compilation is silently discarded at load time — a
 * build that appears to work and is merely slow. A far-future date is used so that this holds even
 * against a source file carrying a real timestamp from somewhere else.
 */
final class Jars {

    /** 2038-01-01T00:00:00Z. Comfortably after any build, and before the 32-bit time_t cliff. */
    private static final long SOURCE_TIME = 2145916800000L;

    /** Class files are stamped later than sources so Clojure prefers them. */
    private static final long CLASS_TIME = SOURCE_TIME + 2000L;

    private Jars() {}

    /**
     * Writes {@code entries} (jar path -> file on disk) to {@code output}.
     *
     * <p>Directory entries are written for every parent, because some tools that read jars expect
     * them and their absence is the sort of thing that only breaks in someone else's toolchain.
     */
    static void write(Path output, Map<String, Path> entries) throws IOException {
        TreeMap<String, Path> sorted = new TreeMap<>(entries);
        Files.createDirectories(output.toAbsolutePath().getParent());

        try (OutputStream out = Files.newOutputStream(output);
                JarOutputStream jar = new JarOutputStream(out)) {
            TreeMap<String, Boolean> directories = new TreeMap<>();
            for (String name : sorted.keySet()) {
                int slash = name.lastIndexOf('/');
                while (slash > 0) {
                    directories.put(name.substring(0, slash + 1), Boolean.TRUE);
                    slash = name.lastIndexOf('/', slash - 1);
                }
            }

            for (String directory : directories.keySet()) {
                JarEntry entry = new JarEntry(directory);
                entry.setTime(SOURCE_TIME);
                jar.putNextEntry(entry);
                jar.closeEntry();
            }

            for (Map.Entry<String, Path> e : sorted.entrySet()) {
                JarEntry entry = new JarEntry(e.getKey());
                entry.setTime(e.getKey().endsWith(".class") ? CLASS_TIME : SOURCE_TIME);
                jar.putNextEntry(entry);
                Files.copy(e.getValue(), jar);
                jar.closeEntry();
            }
        }
    }
}
