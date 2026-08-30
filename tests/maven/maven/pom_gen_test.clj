(ns maven.pom-gen-test
  "The generated pom against the one that was actually deployed.

  The fixture is not a snapshot of this generator's output — it is
  `clj-grpc-0.1.4.pom`, fetched from Clojars, written by `tools.build`'s `write-pom`
  from the `deps.edn` sitting next to it. A golden test against your own output only
  tells you the output stopped changing. This one tells you the rule would have shipped
  the artifact that shipped.

  The property worth the whole file is the dependency set. clj-grpc pins fourteen Netty
  artifacts at top level because tools.deps gives top-level deps absolute precedence
  over transitive pom ranges — that pinning IS the gRPC/Netty version alignment, and
  `netty-transport-native-epoll` appears twice, once per architecture classifier. Every
  plausible way of being clever here loses something:

    resolving the graph          publishes the lockfile's answer, not the library's ask
    deduplicating by artifactId  silently drops one of the two epoll artifacts
    ignoring the `$classifier`   asks for the JVM-only jar, and epoll dies at runtime

  Each of those produces a pom that looks entirely reasonable. Comparing against the
  deployed file as a SET of full coordinates is what makes them failures here.

  The other half of the file is the metadata a human reads on Clojars — description,
  url, scm, licence — and the assertion that the ALIAS deps are absent. The fixture's
  deps.edn carries :test, :build and :bench aliases holding a test runner, tools.build,
  criterium and Pedestal. None of them is a dependency of the library, and a pom that
  named them would make every consumer resolve them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.xml :as xml]))

;; --- getting at the files ---------------------------------------------------

(defn- runfile
  "Resolves a rootpath handed over in the environment to a readable File.

  A java_test runs with the runfiles tree as its working directory, so the value of
  `$(rootpath ...)` is normally openable as-is. TEST_SRCDIR/TEST_WORKSPACE is the
  documented way to say the same thing, and is the fallback rather than the first
  choice only because the direct path is the one that keeps working when the runfiles
  layout changes underneath us."
  [variable]
  (let [path (or (System/getenv variable)
                 (throw (ex-info (str variable " is not set; the BUILD file should pass it"
                                      " as a $(rootpath ...) in `env`")
                                 {:variable variable})))
        direct (io/file path)]
    (if (.isFile direct)
      direct
      (let [via-srcdir (io/file (System/getenv "TEST_SRCDIR")
                                (System/getenv "TEST_WORKSPACE")
                                path)]
        (when-not (.isFile via-srcdir)
          (throw (ex-info (str variable " names no file: " path)
                          {:variable variable :path path :fallback (str via-srcdir)})))
        via-srcdir))))

;; --- reading a pom ----------------------------------------------------------
;;
;; clojure.xml, in core, rather than data.xml: the ruleset's tests must not need a
;; dependency the ruleset itself does not have. Its parse produces {:tag :attrs
;; :content} nodes whose content mixes child elements with whitespace strings, so
;; everything below filters for maps.

(defn- children
  [node tag]
  (filter #(and (map? %) (= tag (:tag %))) (:content node)))

(defn- child
  [node tag]
  (first (children node tag)))

(defn- text
  "The text of an element, or nil when the element is absent."
  [node tag]
  (some-> (child node tag) :content first str str/trim))

(defn- dependency
  "One `<dependency>` as the tuple that identifies the artifact it resolves to.

  The classifier is part of the identity, not decoration: two entries differing only
  in classifier are two different files in the repository."
  [node]
  {:group-id (text node :groupId)
   :artifact-id (text node :artifactId)
   :version (text node :version)
   :classifier (text node :classifier)})

(defn- dependencies
  [pom]
  (set (map dependency (children (child pom :dependencies) :dependency))))

(defn- scm
  [pom]
  (let [node (child pom :scm)]
    {:connection (text node :connection)
     :developer-connection (text node :developerConnection)
     :tag (text node :tag)
     :url (text node :url)}))

(defn- licenses
  [pom]
  (set (for [license (children (child pom :licenses) :license)]
         {:name (text license :name) :url (text license :url)})))

(defn- coordinates
  [pom]
  {:group-id (text pom :groupId)
   :artifact-id (text pom :artifactId)
   :version (text pom :version)
   :name (text pom :name)
   :packaging (text pom :packaging)
   :model-version (text pom :modelVersion)})

(def ^:private generated (delay (xml/parse (runfile "GENERATED_POM"))))
(def ^:private deployed (delay (xml/parse (runfile "DEPLOYED_POM"))))

;; --- the golden comparison --------------------------------------------------

(deftest dependencies-match-the-deployed-pom
  (testing "every dependency clj-grpc 0.1.4 published, and no others"
    (is (= (dependencies @deployed) (dependencies @generated)))))

(deftest the-netty-pin-survives-in-full
  (let [deps (dependencies @generated)]
    (testing "all 22 top-level deps, not a resolved subset"
      (is (= 22 (count deps))))
    (testing "both epoll artifacts, which differ only by classifier"
      (is (= #{{:group-id "io.netty"
                :artifact-id "netty-transport-native-epoll"
                :version "4.2.16.Final"
                :classifier "linux-x86_64"}
               {:group-id "io.netty"
                :artifact-id "netty-transport-native-epoll"
                :version "4.2.16.Final"
                :classifier "linux-aarch_64"}}
             (set (filter #(= "netty-transport-native-epoll" (:artifact-id %)) deps)))))
    (testing "every netty artifact at the one aligned version"
      (is (= #{"4.2.16.Final"}
             (set (map :version (filter #(= "io.netty" (:group-id %)) deps))))))))

(deftest alias-dependencies-are-not-published
  (testing "the :test, :build and :bench aliases are development inputs, and a pom that
            carried them would make every consumer resolve a test runner"
    (let [artifacts (set (map :artifact-id (dependencies @generated)))]
      (doseq [absent ["test-runner" "tools.build" "deps-deploy"
                      "criterium" "jsonista"
                      "pedestal.service" "pedestal.jetty"]]
        (is (not (contains? artifacts absent))
            (str absent " comes from an alias and must not be in the pom"))))))

(deftest metadata-matches-the-deployed-pom
  (testing "coordinates"
    (is (= (coordinates @deployed) (coordinates @generated))))
  (testing "what a person reads on Clojars"
    (is (= (text @deployed :description) (text @generated :description)))
    (is (= (text @deployed :url) (text @generated :url))))
  (testing "scm, derived from one url rather than written out by hand"
    (is (= (scm @deployed) (scm @generated))))
  (testing "licences"
    (is (= (licenses @deployed) (licenses @generated)))))

(deftest the-clojars-repository-is-named
  (testing "a consumer whose pom does not name Clojars gets a resolution failure that
            names an artifact rather than a missing repository"
    (let [repository (child (child @generated :repositories) :repository)]
      (is (= "clojars" (text repository :id)))
      (is (= "https://repo.clojars.org/" (text repository :url))))))

(deftest source-directory-is-the-strip-prefix
  (testing "the deployed pom says `src` because clj-grpc's sources are at src/; this
            fixture's live deeper in this repository, so the value differs from the
            golden file by construction and is checked against the rule's input instead"
    (is (= "tests/maven/fixtures/src"
           (text (child @generated :build) :sourceDirectory)))))

;; --- and the jar the pom describes ------------------------------------------

(defn- jar-entries
  [file]
  (with-open [jar (java.util.jar.JarFile. ^java.io.File file)]
    (into #{} (map #(.getName %)) (enumeration-seq (.entries jar)))))

(deftest the-jar-carries-the-pom-where-maven-puts-it
  (let [entries (jar-entries (runfile "GENERATED_JAR"))]
    (testing "sources, at the path the namespace implies"
      (is (contains? entries "clj_grpc/core.clj")))
    (testing "and the pom, at META-INF/maven/{group}/{artifact}/ — a path no
              strip_prefix can produce, which is why build_jar takes extra_entries"
      (is (contains? entries "META-INF/maven/com.github.bpalermo/clj-grpc/pom.xml"))
      (is (contains? entries "META-INF/maven/com.github.bpalermo/clj-grpc/pom.properties")))))
