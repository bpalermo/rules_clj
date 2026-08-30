(ns rules-clj.pom-gen
  "Writes the pom a `deps.edn` implies.

  A published Clojure library has two descriptions of its dependencies: the `deps.edn`
  its authors edit, and the pom its consumers resolve. They have to agree, and the only
  way to guarantee that is to derive the second from the first — which is what
  `tools.build`'s `write-pom` does, and why a project ends up with a `build.clj` and a
  second toolchain purely to publish. This does the same derivation as a build action,
  so a Bazel project needs neither.

  Three deliberate restrictions, each of which is a correctness property rather than a
  simplification:

  - Only the TOP-LEVEL `:deps` are read. Alias deps (`:test`, `:build`, `:bench`) are
    development inputs; a pom that carried them would make every consumer resolve a test
    runner. `tools.build` reaches the same set by resolving a basis with no aliases; we
    reach it by not looking.

  - Dependencies are emitted VERBATIM — same group, artifact, version, classifier and
    exclusions, no resolution, no version selection. That is the whole point for a
    project that pins its transitive graph by hand: clj-grpc pins fourteen Netty
    artifacts at top level precisely because tools.deps gives top-level deps absolute
    precedence over transitive pom ranges, and a pom that \"helpfully\" resolved them
    would publish the alignment away.

  - No timestamps, and the output is sorted. Two builds of one input produce one byte
    sequence, so the pom is a cacheable action output like any other.

  A dependency with no `:mvn/version` — a `:git/url` or `:local/root` — is an error
  rather than an omission: a pom cannot express it, and a published pom that silently
  lacks a dependency fails in the consumer's build instead of in ours."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- fail
  "Aborts with a message meant for whoever ran the build.

  Throws rather than calling `System/exit` so that every refusal below can be driven
  from a test. `-main` turns this back into an exit code, which is what an action needs;
  a `System/exit` in the middle of the code would take a test JVM with it and leave the
  failure paths — which are most of this file's behaviour — unreachable."
  [message]
  (throw (ex-info (str "pom-gen: " message) {::error true})))

(def ^:private utf-8
  "Every file read and written here is UTF-8, stated rather than inherited.

  `clojure.java.io` does already default to UTF-8 rather than to the platform charset,
  so this is not a behaviour change — it is the guarantee written down at the point it
  is relied on. A pom declares `encoding=\"UTF-8\"` in its first line, and a generated
  file whose bytes depend on the machine that produced it would not be the cacheable,
  reproducible action output the rest of this ruleset promises."
  "UTF-8")

(def ^:private coordinate
  "The characters a group id, artifact id or version may hold.

  What Maven coordinates actually use, and no more. These three strings become directory
  names in a repository and path segments in an upload URL, so one holding a separator or
  a `..` names a place other than the artifact's own."
  #"[A-Za-z0-9_+-][A-Za-z0-9._+-]*")

(defn- check-coordinate
  "Refuses a coordinate component that could not be published, during the BUILD.

  The publisher refuses these too, and has to: it is the thing that writes, so it cannot
  rely on having been handed good input. But a check that lives only there fires at
  release time, on the one artifact whose coordinates were already baked into a pom and a
  jar that `bazel build` reported as fine — which is precisely the boundary this rule
  claims to hold. Checking here means a malformed coordinate fails in CI, months before
  anyone tries to cut a release, rather than during one.

  Dependency coordinates are deliberately NOT checked this way: a dependency's version may
  legitimately be a Maven range such as `[1.0,2.0)`, which is not a path segment of ours
  and not ours to refuse."
  [what value]
  (when-not (and (string? value)
                 (re-matches coordinate value)
                 (not (str/includes? value "..")))
    (fail (str "the " what " is not a usable coordinate: " (pr-str value)
               ". A coordinate may hold letters, digits, '.', '_', '+' and '-', may not"
               " begin with a '.' and may not contain '..', because it becomes a directory"
               " name and a URL path segment."))))

;; --- reading the project ----------------------------------------------------

(defn read-version
  "Returns the version string held in `file`.

  Accepts `{:version \"1.2.3\"}` — the shape a Clojure project already has, where the
  same file feeds a release workflow and a README check — and also a file holding the
  bare version, since a project whose version comes from a generator rarely wraps it in
  a map. Anything else is the text of the file, trimmed.

  A blank or non-string version is refused rather than passed along. It is the one input
  that reaches BOTH the pom and the artifact's file name, so an empty one produces
  `<version></version>`, coordinates reading `group:artifact:`, and a jar called
  `lib-.jar` — while `bazel build` reports success, because writing a nonsense pom is not
  an error to a program that was only asked to write a pom. The first sign of trouble
  would be a repository rejecting the upload, or worse, accepting it."
  [file]
  (let [text (str/trim (slurp file :encoding utf-8))
        parsed (try (edn/read-string text) (catch Exception _ ::unreadable))
        version (cond
                  (map? parsed) (or (:version parsed)
                                    (fail (str file " is a map with no :version key")))
                  (string? parsed) parsed
                  :else text)]
    (when-not (string? version)
      (fail (str file " has a :version that is not a string: " (pr-str version))))
    (when (str/blank? version)
      (fail (str file " holds no version. Expected {:version \"1.2.3\"} or a file"
                 " containing just the version; an empty one would publish coordinates"
                 " with an empty version, which no repository can serve.")))
    (str/trim version)))

(defn- lib->artifact
  "Splits a tools.deps lib symbol into group, artifact and classifier.

  `io.netty/netty-transport-native-epoll$linux-x86_64` is one lib symbol naming one
  Maven artifact with a classifier; the `$` suffix is tools.deps' spelling of
  `<classifier>`. Losing it would publish a pom that asks for the JVM-only jar and then
  fails at runtime looking for epoll — which is exactly the kind of silent difference
  this generator exists to prevent."
  [lib]
  (let [[artifact classifier] (str/split (name lib) #"\$" 2)]
    {:group-id (or (namespace lib) artifact)
     :artifact-id artifact
     :classifier classifier}))

(defn- exclusion
  "A tools.deps exclusion symbol as a Maven exclusion.

  A bare symbol (no namespace) excludes a groupId that equals its artifactId, which is
  the old single-segment convention; `group/*` excludes a whole group, since Maven reads
  `*` as a wildcard and Clojure reads it as a perfectly ordinary symbol name."
  [sym]
  {:group-id (or (namespace sym) (name sym))
   :artifact-id (name sym)})

(defn dependencies
  "The `<dependency>` entries implied by a deps.edn's top-level `:deps`.

  Sorted by coordinate so the output does not depend on map iteration order."
  [deps-edn-path]
  (let [deps (:deps (edn/read-string {:default (fn [_tag value] value)}
                                     (slurp deps-edn-path :encoding utf-8)))]
    (->> deps
         (map (fn [[lib coord]]
                (let [version (:mvn/version coord)]
                  (when (nil? version)
                    (fail (str lib " in " deps-edn-path " has no :mvn/version. A pom can only"
                               " express Maven coordinates, so a :git/url or :local/root"
                               " dependency cannot be published — release it to a repository"
                               " first, or move it into an alias.")))
                  ;; Present is not the same as usable, and neither failure announces itself:
                  ;; an empty string is truthy, so it reaches the pom as <version></version>,
                  ;; which no resolver can satisfy; and an unquoted 1.2 is a NUMBER to the
                  ;; reader, which reaches the renderer as a child that is not a string and
                  ;; not a node, and dies there on destructuring rather than here with a
                  ;; sentence naming the dependency. Deliberately not the coordinate rule
                  ;; applied to the project's own version: a dependency's version may be a
                  ;; Maven range such as [1.0,2.0), which is a legal thing to ask a resolver
                  ;; for and none of our business to refuse.
                  (when-not (and (string? version) (not (str/blank? version)))
                    (fail (str lib " in " deps-edn-path " has a :mvn/version that is not a"
                               " version: " (pr-str version) ". It must be a non-blank string"
                               " — note that an unquoted 1.2 is read as a number, not as"
                               " \"1.2\".")))
                  (assoc (lib->artifact lib)
                         :version version
                         :exclusions (mapv exclusion (:exclusions coord))))))
         (sort-by (juxt :group-id :artifact-id #(or (:classifier %) "")))
         vec)))

;; --- writing the pom --------------------------------------------------------

(defn- escape
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- element
  "Renders a hiccup-ish `[tag child ...]` node, indented.

  A node whose single child is a string goes on one line; anything else nests. nil
  children are dropped, which is what lets the optional parts of a pom be expressed as
  `(when url [:url url])` rather than as conditional string building."
  [node depth]
  (let [pad (apply str (repeat depth "  "))
        [tag & children] node
        children (remove nil? children)]
    (if (and (= 1 (count children)) (string? (first children)))
      (str pad "<" (name tag) ">" (escape (first children)) "</" (name tag) ">\n")
      (str pad "<" (name tag) ">\n"
           (apply str (map #(element % (inc depth)) children))
           pad "</" (name tag) ">\n"))))

(defn- dependency-element
  [{:keys [group-id artifact-id version classifier exclusions]}]
  (into [:dependency
         [:groupId group-id]
         [:artifactId artifact-id]
         [:version version]
         (when classifier [:classifier classifier])]
        (when (seq exclusions)
          [(into [:exclusions]
                 (for [{:keys [group-id artifact-id]} (sort-by (juxt :group-id :artifact-id) exclusions)]
                   [:exclusion [:groupId group-id] [:artifactId artifact-id]]))])))

(defn- scm-element
  "The `<scm>` block, derived from a project URL.

  Given `https://github.com/owner/repo` the git connection strings follow mechanically,
  and every Clojure library's build.clj writes them out by hand. The tag is `v` plus the
  version, which is the convention this ruleset's own release workflow uses; a project
  that tags differently gets a wrong tag in its pom and nothing worse, since nothing
  resolves through it."
  [scm-url version]
  (let [[_ host path] (re-matches #"https?://([^/]+)/(.+?)(?:\.git)?/?" (str scm-url))]
    (into [:scm]
          (concat
           (when (and host path)
             [[:connection (str "scm:git:git://" host "/" path ".git")]
              [:developerConnection (str "scm:git:ssh://git@" host "/" path ".git")]])
           [[:tag (str "v" version)]
            [:url (str scm-url)]]))))

(defn pom
  "Renders the whole pom as a string.

  The element order is Maven's own, and matches what `tools.build`'s `write-pom`
  produces, so a project moving from a `build.clj` to this rule can diff the two poms
  and see only the parts it meant to change."
  [{:keys [group-id artifact-id version description url scm-url
           license-name license-url source-directory deps]}]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<project xmlns=\"http://maven.apache.org/POM/4.0.0\""
       " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
       " xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0"
       " http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
       (apply
        str
        (map #(element % 1)
             (remove nil?
                     (concat
                      [[:modelVersion "4.0.0"]
                       [:packaging "jar"]
                       [:groupId group-id]
                       [:artifactId artifact-id]
                       [:version version]
                       [:name artifact-id]
                       (when (seq description) [:description description])
                       (when (seq url) [:url url])
                       (when (seq license-name)
                         [:licenses (into [:license [:name license-name]]
                                          (when (seq license-url) [[:url license-url]]))])
                       (when (seq scm-url) (scm-element scm-url version))
                       (into [:dependencies] (map dependency-element deps))
                       (when (seq source-directory)
                         [:build [:sourceDirectory source-directory]])]
                      ;; Clojars, always. A Clojure library's transitive dependencies are
                      ;; routinely on Clojars rather than Central, and a consumer whose pom
                      ;; does not name the repository gets a resolution failure naming an
                      ;; artifact rather than a missing repository. An unused repository
                      ;; entry costs a resolver one 404; a missing one costs a person an
                      ;; afternoon.
                      [[:repositories
                        [:repository
                         [:id "clojars"]
                         [:url "https://repo.clojars.org/"]]]]))))
       "</project>\n"))

(def usage
  (str "usage: --deps-edn=F --version-edn=F --group-id=G --artifact-id=A"
       " --pom-out=F --properties-out=F --coordinates-out=F"
       " [--description=..] [--url=..] [--scm-url=..]"
       " [--license-name=..] [--license-url=..] [--source-directory=..]"))

(defn parse-args
  "`--key=value` arguments as a map of keyword to string. Anything else is ignored."
  [args]
  (into {} (for [a args
                 :let [[_ k v] (re-matches #"--([^=]+)=(.*)" a)]
                 :when k]
             [(keyword k) v])))

(defn write!
  "Writes the pom, the properties file and the coordinates named by `opts`.

  Separate from `-main` so a test can call it: it takes a map, writes files and returns
  the version, with no argument parsing and no exit codes in the way."
  [opts]
  (let [{:keys [deps-edn version-edn group-id artifact-id
                pom-out properties-out coordinates-out]} opts]
    ;; Every one of these is written or read unconditionally below, so all of them are
    ;; required — including the two output paths that were once missing from this check.
    ;; The rule always passes them, so the gap was invisible from a build and appeared
    ;; only when someone ran the tool by hand, as a NullPointerException out of `spit`
    ;; rather than as the usage line that would have told them what to add.
    (when-not (and deps-edn version-edn group-id artifact-id
                   pom-out properties-out coordinates-out)
      (fail usage))
    (check-coordinate "group id" group-id)
    (check-coordinate "artifact id" artifact-id)
    (let [version (read-version version-edn)]
      (check-coordinate "version" version)
      (spit pom-out
            (pom (assoc opts :version version :deps (dependencies deps-edn)))
            :encoding utf-8)
      ;; The properties file Maven puts next to the pom inside the jar. No timestamp
      ;; comment, unlike maven-archiver's: the jar has to be byte-identical across
      ;; builds of identical inputs.
      (spit properties-out
            (str "groupId=" group-id "\n"
                 "artifactId=" artifact-id "\n"
                 "version=" version "\n")
            :encoding utf-8)
      ;; The one place the version crosses from a file into a command line. The publish
      ;; rule reads it at run time, which is what keeps the version out of analysis and
      ;; the jar's file name fixed.
      (spit coordinates-out (str group-id ":" artifact-id ":" version "\n")
            :encoding utf-8)
      version)))

(defn -main
  [& args]
  (try
    (write! (parse-args args))
    (shutdown-agents)
    (catch clojure.lang.ExceptionInfo e
      ;; Only our own refusals become an exit code with a message; anything else is a
      ;; bug here and deserves its stack trace.
      (when-not (::error (ex-data e))
        (throw e))
      (binding [*out* *err*] (println (ex-message e)))
      (shutdown-agents)
      (System/exit 1))))
