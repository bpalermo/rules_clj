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

    static void serve() throws IOException {
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
