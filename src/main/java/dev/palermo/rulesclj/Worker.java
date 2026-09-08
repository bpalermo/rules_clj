package dev.palermo.rulesclj;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bazel persistent worker for the Clojure compiler.
 *
 * <p>A worker is one long-lived process that Bazel feeds work requests over stdin. What it saves
 * here is JVM startup and a warm JIT — not class loading, because each request loads its own
 * Clojure in its own classloader (see Aot.Clojure for why that isolation is not optional).
 *
 * <p>Two things make a worker easy to get subtly wrong, and both are handled here rather than
 * discovered later:
 *
 * <ul>
 *   <li><b>stdout belongs to the protocol.</b> Bazel parses it as a stream of JSON responses, so
 *       anything the compiler prints — a warning from Clojure, a stray println in someone's macro
 *       — would corrupt it. System.out is redirected for the duration of each request and its
 *       content returned in the response instead, where Bazel shows it as action output.
 *   <li><b>State must not cross requests.</b> The classloader is dropped after each one, and the
 *       scratch directory is cleared before each one.
 * </ul>
 *
 * <p>Singleplex: one request at a time. Multiplexing would mean concurrent compiles in one JVM,
 * and Clojure's compiler holds global state that makes that a separate problem from this one.
 */
final class Worker {

    /**
     * Whether this JVM pins identity hash codes, which is what makes a warm worker's output
     * deterministic.
     *
     * <p>Clojure's compiler emits locals-clearing instructions by walking maps keyed on objects
     * that use identity hash codes. HotSpot draws those from a per-thread PRNG whose sequence
     * depends on how much the JVM has already done, so a warm worker emits different bytecode
     * than a cold one for identical source. The worker target asks for {@code -XX:hashCode=2},
     * which makes every identity hash 1 and removes the variable.
     *
     * <p>That flag is HotSpot-specific and guarded by {@code -XX:+IgnoreUnrecognizedVMOptions},
     * so a JVM that does not have it still starts and still compiles — it just compiles
     * non-deterministically. Silently is the one thing that must not happen: a build whose
     * outputs vary is a build whose cache hits and image digests lie, and it is invisible
     * without a check. Hence this one, once, at startup.
     */
    private static void warnIfIdentityHashesAreNotPinned(PrintStream err) {
        // Two objects, because one hash of 1 could be a coincidence on a JVM that hands them
        // out sequentially; two in a row could not.
        int first = new Object().hashCode();
        int second = new Object().hashCode();
        if (first == 1 && second == 1) {
            return;
        }
        err.println(
                "rules_clj: this JVM does not honour -XX:hashCode=2, so compilation in a"
                    + " persistent worker is not deterministic: identical sources can produce"
                    + " different bytecode depending on what the worker compiled before. Build"
                    + " with --@rules_clj//clojure:worker=false for reproducible output.");
    }

    static void serve() throws IOException {
        warnIfIdentityHashesAreNotPinned(System.err);
        PrintStream realStdout = System.out;
        Reader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        while (true) {
            Object message = Json.read(in);
            if (message == null) {
                return; // Bazel closed stdin: it is shutting the worker down.
            }
            if (!(message instanceof Map<?, ?> request)) {
                throw new IOException("expected a work request object, got " + message);
            }

            Object requestId = request.get("requestId");
            if (requestId == null) {
                // Absent in singleplex mode, where there is only ever one request in flight.
                requestId = 0L;
            }
            if (Boolean.TRUE.equals(request.get("cancel"))) {
                // Cancellation is not supported; acknowledging it is still better than
                // leaving Bazel waiting for a response that never comes.
                respond(realStdout, requestId, 0, "");
                continue;
            }

            List<String> arguments = new ArrayList<>();
            if (request.get("arguments") instanceof List<?> raw) {
                for (Object argument : raw) {
                    arguments.add(String.valueOf(argument));
                }
            }

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            int exitCode = 0;
            try (PrintStream redirect = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
                System.setOut(redirect);
                try {
                    Aot.compile(Aot.Args.parse(arguments.toArray(new String[0])));
                } catch (Throwable t) {
                    exitCode = 1;
                    t.printStackTrace(redirect);
                } finally {
                    System.setOut(realStdout);
                }
            }
            respond(realStdout, requestId, exitCode, captured.toString(StandardCharsets.UTF_8));
        }
    }

    private static void respond(PrintStream out, Object requestId, int exitCode, String output) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("exitCode", exitCode);
        response.put("output", output);
        response.put("requestId", requestId);
        out.print(Json.writeObject(response));
        out.flush();
    }

    private Worker() {}
}
