package dev.palermo.rulesclj.maven;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uploads a jar and its pom to a Maven repository.
 *
 * <p>Depends on nothing but the JDK, for the same reason the compiler shim does: rules_clj is a
 * ruleset before it is a program, and a ruleset that needs a dependency resolver in order to be
 * built is one every consumer pays for. A deploy is five HTTP PUTs per artifact — the file
 * itself and its four checksums, so ten for a jar and its pom — and the whole of Aether is not
 * required to make them.
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

    /** The scheme that means "install into a directory on this machine" rather than "upload". */
    private static final String FILE_SCHEME = "file:";

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
        // Before anything else, and deliberately inside plan() rather than inside upload():
        // --dry-run returns as soon as the plan exists, so a check that lived in upload()
        // would let a dry run report success for a repository the real run cannot use. A
        // rehearsal that passes where the performance fails is worse than no rehearsal.
        validateRepository(parsed.repository);

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

        // The URLs were assembled from coordinates read out of a file, so the base being a
        // legal URI does not by itself make them ones. Coordinates.read has already refused
        // every character that could make this fail, so like the containment check in
        // upload() this should never fire; it stays because the assembled URL is what the
        // PUT actually uses, and the last thing to look at it before a release should be the
        // thing that knows it is a URL. A `file:` upload never goes near URI, and a
        // filesystem path is allowed to contain anything.
        if (!isFileRepository(parsed.repository)) {
            for (Upload upload : uploads) {
                try {
                    URI.create(upload.url);
                } catch (IllegalArgumentException e) {
                    throw new PublishException(
                            "these coordinates do not make a usable URL: " + upload.url);
                }
            }
        }
        return uploads;
    }

    /** Whether this repository is a directory on this machine rather than somewhere to upload. */
    private static boolean isFileRepository(String repository) {
        return repository.regionMatches(true, 0, FILE_SCHEME, 0, FILE_SCHEME.length());
    }

    /**
     * Checks that the repository is somewhere this can actually send to.
     *
     * <p>Everything here is decidable without touching the network or the credentials, which is
     * the point: it is exactly the set of mistakes a {@code --dry-run} should be able to catch,
     * and every one of them used to be reported only after the dry run had said the plan was
     * fine. A repository that does not resolve, is not http(s), names no host, or is a
     * {@code file:} path that is relative, remote or an existing regular file, is a mistake in
     * the command line rather than a failure of the upload.
     */
    static void validateRepository(String repository) throws PublishException {
        if (isFileRepository(repository)) {
            Path root = filePath(repository);
            if (Files.exists(root) && !Files.isDirectory(root)) {
                throw new PublishException("a file: repository must be a directory: " + root);
            }
            return;
        }

        URI uri;
        try {
            uri = new URI(repository);
        } catch (URISyntaxException e) {
            throw new PublishException(
                    "not a usable repository URL: " + repository + " (" + e.getReason() + ")");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new PublishException(
                    "the repository needs a scheme, e.g. https://repo.example.com or"
                            + " file:///path/to/repository, got: "
                            + repository);
        }
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            throw new PublishException(
                    "a repository must be http, https or file:, got "
                            + scheme
                            + ": "
                            + repository);
        }
        if (uri.getHost() == null) {
            throw new PublishException("the repository URL names no host: " + repository);
        }
        if (scheme.equalsIgnoreCase("http") && !isLoopback(uri.getHost())) {
            throw new PublishException(
                    "refusing to publish over plain http to "
                            + uri.getHost()
                            + ": the credentials travel in an Authorization header, which http"
                            + " sends in clear text to anyone on the path, and a deploy token"
                            + " that leaks is a token someone else can publish with. Use https,"
                            + " or file: for a local install. http is allowed only to loopback,"
                            + " for testing against a repository on this machine.");
        }
    }

    /** A dotted quad, with the range of each octet left to {@link #isLoopback}. */
    private static final Pattern IPV4 =
            Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    /**
     * Whether this host is this machine, and therefore somewhere plain http cannot leak to.
     *
     * <p>The carve-out exists because a repository run on the developer's own machine for a test
     * is the one case where http is not a mistake, and there the request never reaches a network.
     *
     * <p>It has to be exact, because everything it admits skips the https requirement. A prefix
     * test such as {@code startsWith("127.")} reads as "in 127.0.0.0/8" and is not: {@code
     * 127.attacker.example} is a perfectly ordinary hostname that resolves wherever its owner
     * points it, and would have taken the deploy token there in clear text. So the name forms are
     * compared whole, and the numeric form is parsed as four octets rather than matched as text.
     */
    private static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost") || host.equals("::1") || host.equals("[::1]")) {
            return true;
        }
        Matcher address = IPV4.matcher(host);
        if (!address.matches()) {
            return false;
        }
        for (int group = 1; group <= 4; group++) {
            if (Integer.parseInt(address.group(group)) > 255) {
                return false;
            }
        }
        return Integer.parseInt(address.group(1)) == 127;
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
        if (isFileRepository(parsed.repository)) {
            Path root = filePath(parsed.repository).toAbsolutePath().normalize();
            for (Upload upload : uploads) {
                Path destination =
                        root.resolve(relativeTo(parsed.repository, upload.url)).normalize();
                // Coordinates.read has already refused every component that could get out of
                // here, so this cannot fire today. It stays because it is the check that makes
                // that true independently of the parser: this is the line that writes to the
                // filesystem, and it should be the line that knows where it is allowed to write.
                if (!destination.startsWith(root)) {
                    throw new PublishException(
                            "refusing to write outside the repository: "
                                    + destination
                                    + " is not under "
                                    + root);
                }
                Files.createDirectories(destination.getParent());
                Files.write(destination, upload.content);
                out.println("wrote " + destination);
            }
            out.printf("installed %d files into %s%n", uploads.size(), root);
            return 0;
        }

        Credentials credentials = Credentials.fromEnvironment();
        if (credentials == null) {
            String half = Credentials.halfConfigured(System::getenv);
            err.println(
                    "publisher: no credentials. Set CLOJARS_USERNAME and CLOJARS_PASSWORD (or"
                            + " MAVEN_USER and MAVEN_PASSWORD). On Clojars the password must be a"
                            + " deploy token, not your account password."
                            + (half == null
                                    ? ""
                                    : " Note that "
                                            + half
                                            + ": a username and a password are taken from the same"
                                            + " pair, never one from each."));
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
     * <p>Two spellings are both common and both meant: {@code file:///abs/path}, which is the
     * well-formed URL, and the abbreviated {@code file:/abs/path}, which is what a person types.
     * Neither is reliably a legal URI, because the thing on the end is a filesystem path and
     * filesystem paths contain spaces. So this tries the strict reading first and falls back to
     * the literal one, rather than assembling a URI out of string pieces and hoping:
     *
     * <ul>
     *   <li><b>A parseable {@code file:} URI</b> is handed to {@code Paths.get(URI)}, which is
     *       the only thing here that knows how to percent-decode — so {@code file:///tmp/a%20b}
     *       resolves to the directory {@code /tmp/a b}, as a URL should.
     *   <li><b>Anything else</b> is treated as a literal path after the scheme and any empty or
     *       {@code localhost} authority. {@code file:///home/me/my repository} is not a legal
     *       URI and is not remotely ambiguous; refusing it would be pedantry with a stack trace.
     * </ul>
     *
     * <p>Both readings agree wherever both apply, which is what makes the fallback safe.
     */
    static Path filePath(String repository) throws PublishException {
        String stripped = stripTrailingSlash(repository);
        if (!stripped.regionMatches(true, 0, FILE_SCHEME, 0, FILE_SCHEME.length())) {
            throw new PublishException("not a file: repository: " + repository);
        }

        try {
            URI uri = new URI(stripped);
            if (uri.getPath() != null && !uri.getPath().isEmpty()) {
                return Paths.get(uri);
            }
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            // Not a URI, or a URI this filesystem will not take — an unencoded space reaches
            // here, and so does a remote authority. The literal reading below handles the
            // first and rejects the second with something to say.
        }

        String path = stripped.substring(FILE_SCHEME.length());
        if (path.startsWith("//")) {
            // file://<authority>/path. Only an empty authority or localhost names THIS
            // machine, and a copy cannot install to another one.
            int slash = path.indexOf('/', 2);
            String authority = slash < 0 ? path.substring(2) : path.substring(2, slash);
            if (!authority.isEmpty() && !authority.equalsIgnoreCase("localhost")) {
                throw new PublishException(
                        "a file: repository cannot name another host ("
                                + authority
                                + "): "
                                + repository);
            }
            path = slash < 0 ? "" : path.substring(slash);
        }
        if (!path.startsWith("/")) {
            throw new PublishException(
                    "a file: repository must be an absolute path, got: " + repository);
        }
        try {
            return Paths.get(path);
        } catch (InvalidPathException e) {
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

        /**
         * The characters a coordinate component may hold.
         *
         * <p>Narrower than "not empty", because these three strings are not only printed: the
         * group's dots become slashes and all three become directory names under a {@code file:}
         * repository and path segments in an upload URL. A component holding a separator or a
         * {@code ..} would therefore name a place other than the one being published to, and
         * {@code Path.resolve} would follow it out of the repository with {@code Files.write}
         * behind it. The set below is what Maven coordinates actually use, so refusing everything
         * else costs a real release nothing and removes the question of where a write can land.
         */
        private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_+-][A-Za-z0-9._+-]*");

        static Coordinates read(Path file) throws IOException, PublishException {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            String[] parts = text.split(":");
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new PublishException(
                        "expected group:artifact:version in " + file + ", found: " + text);
            }
            check(file, "group id", parts[0]);
            check(file, "artifact id", parts[1]);
            check(file, "version", parts[2]);
            return new Coordinates(parts[0], parts[1], parts[2]);
        }

        private static void check(Path file, String what, String value) throws PublishException {
            if (!COMPONENT.matcher(value).matches() || value.contains("..")) {
                throw new PublishException(
                        "the "
                                + what
                                + " in "
                                + file
                                + " is not a usable coordinate: "
                                + value
                                + ". A coordinate may hold letters, digits, '.', '_', '+' and '-',"
                                + " may not begin with a '.' and may not contain '..', because it"
                                + " becomes a directory name and a URL path segment.");
            }
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

        /**
         * The first COMPLETE pair, rather than the first set variable of each kind.
         *
         * <p>Choosing the username and the password independently mixes them: with
         * {@code CLOJARS_USERNAME} set and {@code CLOJARS_PASSWORD} forgotten, the fallback
         * supplies {@code MAVEN_PASSWORD}, and the result is one repository's secret sent to
         * another repository's server under the first one's username. The failure is silent and
         * points the wrong way — the server answers 401, so the reader concludes the token is
         * wrong rather than that it went somewhere it should never have been. A secret is only
         * ever as safe as the least careful thing that knows it, and a half-configured
         * environment is not a reason to improvise one.
         */
        static Credentials fromEnvironment() {
            return from(System::getenv);
        }

        /** Separate from {@link #fromEnvironment} so a test can drive it without a process env. */
        static Credentials from(UnaryOperator<String> environment) {
            Credentials clojars = pair(environment, "CLOJARS_USERNAME", "CLOJARS_PASSWORD");
            return clojars != null ? clojars : pair(environment, "MAVEN_USER", "MAVEN_PASSWORD");
        }

        /**
         * A sentence naming a pair with exactly one half set, or null if neither is.
         *
         * <p>Worth saying, because the mistake it describes used to be invisible: the run would
         * proceed with a mixed pair and fail at the server with a 401, which reads as "the token
         * is wrong" rather than "the token was not the one you meant".
         */
        static String halfConfigured(UnaryOperator<String> environment) {
            String clojars = missingHalf(environment, "CLOJARS_USERNAME", "CLOJARS_PASSWORD");
            return clojars != null
                    ? clojars
                    : missingHalf(environment, "MAVEN_USER", "MAVEN_PASSWORD");
        }

        private static String missingHalf(
                UnaryOperator<String> environment, String userVariable, String passwordVariable) {
            boolean user = value(environment, userVariable) != null;
            boolean password = value(environment, passwordVariable) != null;
            if (user == password) {
                return null;
            }
            return user
                    ? passwordVariable + " is not set, though " + userVariable + " is"
                    : userVariable + " is not set, though " + passwordVariable + " is";
        }

        private static Credentials pair(
                UnaryOperator<String> environment, String userVariable, String passwordVariable) {
            String user = value(environment, userVariable);
            String password = value(environment, passwordVariable);
            return user == null || password == null ? null : new Credentials(user, password);
        }

        private static String value(UnaryOperator<String> environment, String name) {
            String value = environment.apply(name);
            return value == null || value.isEmpty() ? null : value;
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
