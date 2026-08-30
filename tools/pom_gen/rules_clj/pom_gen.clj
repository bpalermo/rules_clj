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
  [message]
  (binding [*out* *err*] (println (str "pom-gen: " message)))
  (System/exit 1))

;; --- reading the project ----------------------------------------------------

(defn read-version
  "Returns the version string held in `file`.

  Accepts `{:version \"1.2.3\"}` — the shape a Clojure project already has, where the
  same file feeds a release workflow and a README check — and also a file holding the
  bare version, since a project whose version comes from a generator rarely wraps it in
  a map. Anything else is the text of the file, trimmed."
  [file]
  (let [text (str/trim (slurp file))
        parsed (try (edn/read-string text) (catch Exception _ ::unreadable))]
    (cond
      (map? parsed) (or (:version parsed)
                        (fail (str file " is a map with no :version key")))
      (string? parsed) parsed
      :else text)))

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
                                     (slurp deps-edn-path)))]
    (->> deps
         (map (fn [[lib coord]]
                (when-not (:mvn/version coord)
                  (fail (str lib " in " deps-edn-path " has no :mvn/version. A pom can only"
                             " express Maven coordinates, so a :git/url or :local/root"
                             " dependency cannot be published — release it to a repository"
                             " first, or move it into an alias.")))
                (assoc (lib->artifact lib)
                       :version (:mvn/version coord)
                       :exclusions (mapv exclusion (:exclusions coord)))))
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

(defn -main
  [& args]
  (let [opts (into {} (for [a args
                            :let [[_ k v] (re-matches #"--([^=]+)=(.*)" a)]
                            :when k]
                        [(keyword k) v]))
        {:keys [deps-edn version-edn group-id artifact-id
                pom-out properties-out coordinates-out]} opts]
    (when-not (and deps-edn version-edn group-id artifact-id pom-out)
      (fail (str "usage: --deps-edn=F --version-edn=F --group-id=G --artifact-id=A"
                 " --pom-out=F --properties-out=F --coordinates-out=F"
                 " [--description=..] [--url=..] [--scm-url=..]"
                 " [--license-name=..] [--license-url=..] [--source-directory=..]")))
    (let [version (read-version version-edn)]
      (spit pom-out (pom (assoc opts
                                :version version
                                :deps (dependencies deps-edn))))
      ;; The properties file Maven puts next to the pom inside the jar. No timestamp
      ;; comment, unlike maven-archiver's: the jar has to be byte-identical across
      ;; builds of identical inputs.
      (spit properties-out
            (str "groupId=" group-id "\n"
                 "artifactId=" artifact-id "\n"
                 "version=" version "\n"))
      ;; The one place the version crosses from a file into a command line. The publish
      ;; rule reads it at run time, which is what keeps the version out of analysis and
      ;; the jar's file name fixed.
      (spit coordinates-out (str group-id ":" artifact-id ":" version "\n")))
    (shutdown-agents)))
