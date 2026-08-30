package dev.palermo.rulesclj.maven;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uploads a jar and its pom to a Maven repository.
 *
 * <p>Depends on nothing but the JDK, for the same reason the compiler shim does: rules_clj is a
 * ruleset before it is a program, and a ruleset that needs a dependency resolver in order to be
 * built is one every consumer pays for. A deploy is three or four HTTP PUTs per artifact — the
 * whole of Aether is not required to make them.
 *
 * <p>The layout below is simply Maven's, so any tool that speaks it agrees on where a file goes:
 * {@code {repository}/{group with dots as slashes}/{artifact}/{version}/{artifact}-{version}.{ext}}
 * with a sibling file per checksum algorithm. rules_jvm_external's {@code MavenPublisher}
 * (Apache-2.0) solves the same problem the same way; this is an independent implementation, but
 * the resemblance in shape is not a coincidence and is worth crediting.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <ul>
 *   <li><b>No GPG.</b> Clojars does not require signatures and Maven Central's requirements go
 *       well beyond a signature; a half-implemented signing story is worse than an absent one.
 *   <li><b>No {@code maven-metadata.xml}.</b> Release versions do not need it — the repository
 *       derives its own — and writing a wrong one is how a repository ends up claiming versions
 *       that were never published.
 *   <li><b>No redirects.</b> A 3xx on an upload is reported rather than followed: the response
 *       that moves an authenticated PUT elsewhere is exactly the one worth looking at by hand.
 * </ul>
 */
public final class Publisher {

    /**
     * Clojars' DEPLOY endpoint, which is not the endpoint artifacts are READ from
     * (https://repo.clojars.org). Getting these the wrong way round produces a 405 on upload,
     * which reads like a broken tool rather than a wrong URL.
     */
    static final String DEFAULT_REPOSITORY = "https://clojars.org/repo";

    /**
     * Checksums to write beside each artifact.
     *
     * <p>md5 and sha1 because every repository and every client still expects them; sha256 and
     * sha512 because they are what anyone actually verifying an artifact today would use. They are
     * cheap — the file is already in memory — so publishing the weak pair alone would be a choice
     * to be less useful for no saving.
     */
    private static final Map<String, String> CHECKSUMS =
            Map.of("md5", "MD5", "sha1", "SHA-1", "sha256", "SHA-256", "sha512", "SHA-512");

    /** Checksum extensions in a fixed order, so output and uploads are reproducible. */
    private static final List<String> CHECKSUM_ORDER = List.of("md5", "sha1", "sha256", "sha512");

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs a publish and returns a process exit code.
     *
     * <p>Public, and separate from {@link #main}, so a test can drive it in-process: the streams
     * are arguments rather than {@code System.out}, and it returns the exit code rather than
     * calling {@code System.exit} and taking the test JVM with it. Every assertion about what this
     * tool prints and writes is then a plain function call.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("publisher: " + e.getMessage());
            err.println(Args.USAGE);
            return 2;
        }

        try {
            List<Upload> uploads = plan(parsed);
            if (parsed.dryRun) {
                for (Upload upload : uploads) {
                    out.printf("PUT %s (%d bytes)%n", upload.url, upload.content.length);
                }
                out.printf("%d files would be uploaded; nothing was sent.%n", uploads.size());
                return 0;
            }
            return upload(parsed, uploads, out, err);
        } catch (PublishException e) {
            err.println("publisher: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("publisher: " + e);
            return 1;
        }
    }

    /**
     * Works out every file that will be sent, before any of them is.
     *
     * <p>Planning first is what makes {@code --dry-run} tell the truth: it prints the same list the
     * real run walks, rather than a description of what the code is believed to do.
     */
    static List<Upload> plan(Args parsed) throws IOException, PublishException {
        Coordinates coordinates = Coordinates.read(parsed.coordinates);
        String base =
                stripTrailingSlash(parsed.repository)
                        + "/"
                        + coordinates.groupId.replace('.', '/')
                        + "/"
                        + coordinates.artifactId
                        + "/"
                        + coordinates.version
                        + "/";
        String stem = coordinates.artifactId + "-" + coordinates.version;

        List<Upload> uploads = new ArrayList<>();

        // The jar goes first and the pom last. A repository that indexes on seeing a pom then
        // never sees one whose jar is missing, which is the failure mode worth avoiding: a
        // half-published version is not something Clojars lets you take back.
        addArtifact(uploads, base, stem + ".jar", parsed.jar);
        addArtifact(uploads, base, stem + ".pom", parsed.pom);
        return uploads;
    }

    private static void addArtifact(List<Upload> uploads, String base, String name, Path file)
            throws IOException, PublishException {
        if (!Files.isRegularFile(file)) {
            throw new PublishException("not a file: " + file);
        }
        byte[] content = Files.readAllBytes(file);
        uploads.add(new Upload(base + name, content, "application/octet-stream"));
        for (String extension : CHECKSUM_ORDER) {
            byte[] digest = hex(digest(CHECKSUMS.get(extension), content));
            uploads.add(new Upload(base + name + "." + extension, digest, "text/plain"));
        }
    }

    private static int upload(Args parsed, List<Upload> uploads, PrintStream out, PrintStream err)
            throws Exception {
        if (parsed.repository.startsWith("file:")) {
            Path root = filePath(parsed.repository);
            for (Upload upload : uploads) {
                Path destination = root.resolve(relativeTo(parsed.repository, upload.url));
                Files.createDirectories(destination.getParent());
                Files.write(destination, upload.content);
                out.println("wrote " + destination);
            }
            out.printf("installed %d files into %s%n", uploads.size(), root);
            return 0;
        }

        Credentials credentials = Credentials.fromEnvironment();
        if (credentials == null) {
            err.println(
                    "publisher: no credentials. Set CLOJARS_USERNAME and CLOJARS_PASSWORD (or"
                            + " MAVEN_USER and MAVEN_PASSWORD). On Clojars the password must be a"
                            + " deploy token, not your account password.");
            return 1;
        }

        HttpClient client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build();

        for (Upload upload : uploads) {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(upload.url))
                            .header("Authorization", credentials.basic())
                            .header("Content-Type", upload.contentType)
                            .timeout(Duration.ofMinutes(5))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(upload.content))
                            .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                out.printf("PUT %s -> %d%n", upload.url, status);
                continue;
            }
            err.println(explain(status, upload.url, response));
            return 1;
        }

        out.printf("published %d files to %s%n", uploads.size(), parsed.repository);
        return 0;
    }

    /**
     * Turns an HTTP status into the sentence the person running the deploy needs.
     *
     * <p>The two that matter are 401 and 403, and they are routinely confused. 401 means the
     * credentials were not accepted at all; 403 on Clojars almost always means the credentials were
     * fine and the version already exists, because released versions are immutable there. Printing
     * "403 Forbidden" and leaving the reader to guess wastes an afternoon.
     */
    private static String explain(int status, String url, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().strip();
        StringBuilder message = new StringBuilder("publisher: PUT ").append(url).append(" failed");
        switch (status) {
            case 401 ->
                    message.append(
                            " with 401 Unauthorized: the repository rejected the credentials."
                                + " CLOJARS_USERNAME must be your username and CLOJARS_PASSWORD a"
                                + " deploy token with the `deploy` scope for this group.");
            case 403 ->
                    message.append(
                            " with 403 Forbidden: usually a version that already exists. Clojars"
                                + " releases are immutable — publish a new version rather than"
                                + " redeploying this one. It can also mean the token lacks"
                                + " permission for this group.");
            default -> {
                message.append(" with ").append(status).append(".");
                if (status >= 300 && status < 400) {
                    message.append(
                            " Redirects are not followed on upload; check the repository URL (the"
                                + " Clojars DEPLOY url is "
                                    + DEFAULT_REPOSITORY
                                    + ", not repo.clojars.org). Location: "
                                    + response.headers().firstValue("location").orElse("(none)"));
                }
            }
        }
        if (!body.isEmpty()) {
            message.append("\n").append(body.length() > 2000 ? body.substring(0, 2000) : body);
        }
        return message.toString();
    }

    /** The path a `file:` upload lands at, relative to the repository root. */
    private static String relativeTo(String repository, String url) {
        String base = stripTrailingSlash(repository) + "/";
        if (!url.startsWith(base)) {
            throw new IllegalStateException(url + " is not under " + base);
        }
        return url.substring(base.length());
    }

    /**
     * Resolves a {@code file:} repository URL to a local directory.
     *
     * <p>Both {@code file:///abs/path} and the abbreviated {@code file:/abs/path} appear in the
     * wild — the second is what a person types — so the URI is normalised rather than trusted.
     */
    static Path filePath(String repository) throws PublishException {
        String path = stripTrailingSlash(repository).substring("file:".length());
        while (path.startsWith("//")) {
            path = path.substring(1);
        }
        if (!path.startsWith("/")) {
            throw new PublishException(
                    "a file: repository must be an absolute path, got: " + repository);
        }
        try {
            return Paths.get(new URI("file://" + path));
        } catch (URISyntaxException e) {
            throw new PublishException("not a usable file: URL: " + repository);
        }
    }

    private static String stripTrailingSlash(String url) {
        String stripped = url;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static byte[] digest(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK is missing " + algorithm, e);
        }
    }

    /**
     * A checksum file holds the lowercase hex digest and nothing else — no trailing newline, no
     * file name. Maven's own clients accept a filename suffix but not every one writes it, and the
     * bare digest is what they all read.
     */
    private static byte[] hex(byte[] digest) {
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            builder.append(Character.forDigit((b >> 4) & 0xf, 16));
            builder.append(Character.forDigit(b & 0xf, 16));
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One file to put at one URL. */
    static final class Upload {
        final String url;
        final byte[] content;
        final String contentType;

        Upload(String url, byte[] content, String contentType) {
            this.url = url;
            this.content = content;
            this.contentType = contentType;
        }
    }

    /** A failure with something to say to the person who ran the command. */
    static final class PublishException extends Exception {
        PublishException(String message) {
            super(message);
        }
    }

    /** group:artifact:version, as written by the pom generator. */
    static final class Coordinates {
        final String groupId;
        final String artifactId;
        final String version;

        private Coordinates(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        static Coordinates read(Path file) throws IOException, PublishException {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            String[] parts = text.split(":");
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new PublishException(
                        "expected group:artifact:version in " + file + ", found: " + text);
            }
            return new Coordinates(parts[0], parts[1], parts[2]);
        }
    }

    /**
     * Repository credentials, read from the environment at run time.
     *
     * <p>Read HERE and never by Bazel. A secret that reaches an action's environment reaches the
     * action cache key and the execution log with it; keeping it in the process that makes the
     * request means the build graph never contains it, which is why publishing is a
     * {@code bazel run} leaf rather than a build step.
     */
    static final class Credentials {
        final String user;
        final String password;

        private Credentials(String user, String password) {
            this.user = user;
            this.password = password;
        }

        static Credentials fromEnvironment() {
            String user = firstSet("CLOJARS_USERNAME", "MAVEN_USER");
            String password = firstSet("CLOJARS_PASSWORD", "MAVEN_PASSWORD");
            return user == null || password == null ? null : new Credentials(user, password);
        }

        private static String firstSet(String... names) {
            for (String name : names) {
                String value = System.getenv(name);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
            return null;
        }

        String basic() {
            String pair = user + ":" + password;
            return "Basic "
                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The command line. */
    static final class Args {
        static final String USAGE =
                "usage: Publisher --coordinates=FILE --pom=FILE --jar=FILE"
                        + " [--repository=URL] [--dry-run]";

        String repository = DEFAULT_REPOSITORY;
        Path coordinates;
        Path pom;
        Path jar;
        boolean dryRun;

        static Args parse(String[] argv) {
            Args args = new Args();
            Map<String, String> flags = new LinkedHashMap<>();
            for (String arg : argv) {
                if (arg.equals("--dry-run")) {
                    args.dryRun = true;
                    continue;
                }
                int eq = arg.indexOf('=');
                if (!arg.startsWith("--") || eq < 0) {
                    throw new IllegalArgumentException("unrecognised argument: " + arg);
                }
                flags.put(arg.substring(2, eq), arg.substring(eq + 1));
            }
            for (Map.Entry<String, String> flag : flags.entrySet()) {
                switch (flag.getKey()) {
                    case "repository" -> args.repository = flag.getValue();
                    case "coordinates" -> args.coordinates = Paths.get(flag.getValue());
                    case "pom" -> args.pom = Paths.get(flag.getValue());
                    case "jar" -> args.jar = Paths.get(flag.getValue());
                    default -> throw new IllegalArgumentException("unknown flag: --" + flag.getKey());
                }
            }
            require(args.coordinates, "--coordinates");
            require(args.pom, "--pom");
            require(args.jar, "--jar");
            return args;
        }

        private static void require(Path value, String flag) {
            if (value == null) {
                throw new IllegalArgumentException(flag + " is required");
            }
        }
    }

    private Publisher() {}
}
