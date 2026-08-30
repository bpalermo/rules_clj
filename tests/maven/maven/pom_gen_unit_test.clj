(ns maven.pom-gen-unit-test
  "The pom generator's refusals, and the encoding of what it writes.

  //tests/maven:pom_gen_test drives the generator through the rule and compares the
  result with a pom that shipped. It cannot reach any of the behaviour here, because
  every case below is one where the generator is supposed to REFUSE — and a BUILD file
  that produced a blank version would have to build successfully before the test could
  look at it.

  That is why `fail` throws rather than calling `System/exit`: a refusal is the most
  important thing this tool does, and the version of it that ends the JVM is the version
  that cannot be tested."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rules-clj.pom-gen :as pom-gen])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir
  []
  (.toFile (Files/createTempDirectory "pom-gen" (make-array FileAttribute 0))))

(defn- file-holding
  "A file in a fresh temp directory containing exactly `content`."
  [name content]
  (let [file (io/file (temp-dir) name)]
    (spit file content :encoding "UTF-8")
    (str file)))

(defn- refusal
  "Runs `f`, and returns the message of the refusal it is expected to throw."
  [f]
  (try
    (f)
    ::no-refusal
    (catch clojure.lang.ExceptionInfo e
      (ex-message e))))

;; --- the version ------------------------------------------------------------

(deftest a-version-is-read-from-either-shape-a-project-uses
  (testing "the map, which is what a Clojure project already has"
    (is (= "1.2.3" (pom-gen/read-version (file-holding "version.edn" "{:version \"1.2.3\"}")))))
  (testing "a bare string, for a version that came out of a generator"
    (is (= "1.2.3" (pom-gen/read-version (file-holding "version.edn" "\"1.2.3\"")))))
  (testing "or unquoted text, which is what a shell script writes"
    (is (= "1.2.3" (pom-gen/read-version (file-holding "version.edn" "1.2.3\n")))))
  (testing "surrounding whitespace is not part of a version"
    (is (= "1.2.3" (pom-gen/read-version (file-holding "version.edn" "  1.2.3  \n"))))))

(deftest a-version-that-is-not-a-version-is-refused
  (testing "an empty version reaches BOTH the pom and the artifact's file name, so it
            produces <version></version>, coordinates ending in a colon, and a jar called
            lib-.jar — while the build reports success, because writing a nonsense pom is
            not an error to a program that was only asked to write a pom"
    (doseq [[label content] [["an empty file" ""]
                             ["whitespace only" "   \n  "]
                             ["an empty string" "\"\""]
                             ["an empty :version" "{:version \"\"}"]
                             ["a blank :version" "{:version \"   \"}"]]]
      (let [message (refusal #(pom-gen/read-version (file-holding "version.edn" content)))]
        (is (str/includes? (str message) "holds no version") label))))

  (testing "a :version that is not a string names what it found, since the likely cause
            is a number or a symbol that looked like a version while being written"
    (doseq [[label content] [["a number" "{:version 1.2}"]
                             ["a symbol" "{:version v1.2.3}"]
                             ["a vector" "{:version [1 2 3]}"]]]
      (let [message (refusal #(pom-gen/read-version (file-holding "version.edn" content)))]
        (is (str/includes? (str message) "not a string") label))))

  (testing "and a map with no :version at all says so specifically"
    (is (str/includes?
         (str (refusal #(pom-gen/read-version (file-holding "version.edn" "{:name \"x\"}"))))
         "no :version key"))))

;; --- dependencies -----------------------------------------------------------

(deftest a-dependency-a-pom-cannot-express-is-an-error
  (testing "a :git/url dependency has no Maven coordinates, and a published pom that
            silently lacked it would fail in the consumer's build rather than in ours"
    (let [deps (file-holding "deps.edn"
                             (str "{:deps {io.github.owner/lib"
                                  " {:git/tag \"v1\" :git/sha \"abc1234\"}}}"))]
      (is (str/includes? (str (refusal #(pom-gen/dependencies deps))) "has no :mvn/version")))))

(deftest exclusions-are-projected-in-every-spelling
  (let [deps (file-holding "deps.edn"
                           (str "{:deps {a/b {:mvn/version \"1\""
                                "  :exclusions [c/d bare e/*]}}}"))
        exclusions (:exclusions (first (pom-gen/dependencies deps)))]
    (testing "namespaced, bare (group == artifact) and whole-group wildcard"
      (is (= #{{:group-id "c" :artifact-id "d"}
               {:group-id "bare" :artifact-id "bare"}
               {:group-id "e" :artifact-id "*"}}
             (set exclusions))))))

;; --- what gets written ------------------------------------------------------

(defn- write-fixture!
  "Runs the generator into a fresh temp directory and returns the three output files."
  [extra]
  (let [out (temp-dir)
        outputs {:pom-out (str (io/file out "pom.xml"))
                 :properties-out (str (io/file out "pom.properties"))
                 :coordinates-out (str (io/file out "coordinates.txt"))}]
    (pom-gen/write!
     (merge {:deps-edn (file-holding "deps.edn" "{:deps {org.clojure/clojure {:mvn/version \"1.12.1\"}}}")
             :version-edn (file-holding "version.edn" "{:version \"1.2.3\"}")
             :group-id "dev.palermo.test"
             :artifact-id "lib"}
            outputs
            extra))
    (into {} (for [[k path] outputs] [k (io/file path)]))))

(deftest a-coordinate-that-could-not-be-published-is-refused-at-build-time
  (testing "the three components become directory names in a repository and path segments
            in an upload URL. The publisher refuses these too, but a check that lives only
            there fires at release time, on a pom and a jar the build already called fine —
            so it is checked here, where CI runs, months before anyone cuts a release"
    (doseq [[what extra]
            [["group id" {:group-id "../../evil"}]
             ["group id" {:group-id ".."}]
             ["group id" {:group-id ""}]
             ["artifact id" {:artifact-id "a/b"}]
             ["artifact id" {:artifact-id "../out"}]
             ["version" {:version-edn (file-holding "version.edn" "{:version \"1.0 SNAPSHOT\"}")}]
             ["version" {:version-edn (file-holding "version.edn" "{:version \"../../../x\"}")}]
             ["version" {:version-edn (file-holding "version.edn" "{:version \"/tmp/abs\"}")}]]]
      (let [message (str (refusal #(write-fixture! extra)))]
        (is (str/includes? message (str "the " what " is not a usable coordinate"))
            (str extra " => " message))))))

(deftest a-dependency-version-may-be-a-maven-range
  (testing "a dependency's version is not a path segment of ours, so the coordinate rule
            must not reach it: [1.0,2.0) is a legal thing to ask a resolver for"
    (let [deps (file-holding "deps.edn" "{:deps {a/b {:mvn/version \"[1.0,2.0)\"}}}")
          {:keys [pom-out]} (write-fixture! {:deps-edn deps})]
      (is (str/includes? (slurp pom-out) "<version>[1.0,2.0)</version>")))))

(deftest every-generated-file-is-utf-8
  (testing "a pom declares encoding=\"UTF-8\" in its first line, so its bytes must be
            UTF-8 whatever charset the machine that ran the action happens to prefer.
            Asserted on the RAW BYTES: a one-byte-per-character encoding of the same
            string would read back as the same text through a lenient decoder while
            being a different file."
    (let [text "naïve café 日本語 — ok"
          {:keys [pom-out]} (write-fixture! {:description text})
          bytes (Files/readAllBytes (.toPath pom-out))
          expected (.getBytes text StandardCharsets/UTF_8)]
      (is (str/includes? (String. bytes StandardCharsets/UTF_8) text))
      (is (str/includes? (String. bytes StandardCharsets/ISO_8859_1)
                         (String. expected StandardCharsets/ISO_8859_1))
          "the file must contain the UTF-8 byte sequence for the description")
      (is (< (count text) (alength expected))
          "the fixture text has to be non-ASCII for any of this to mean anything"))))

(deftest the-outputs-say-what-they-are-supposed-to-say
  (let [{:keys [pom-out properties-out coordinates-out]} (write-fixture! {})]
    (is (= "dev.palermo.test:lib:1.2.3\n" (slurp coordinates-out :encoding "UTF-8")))
    (is (= (str "groupId=dev.palermo.test\n" "artifactId=lib\n" "version=1.2.3\n")
           (slurp properties-out :encoding "UTF-8")))
    (testing "and the pom is byte-identical across runs of identical inputs — no
              timestamps, which is what makes it a cacheable action output"
      (is (= (slurp pom-out :encoding "UTF-8")
             (slurp (:pom-out (write-fixture! {})) :encoding "UTF-8"))))))

(deftest every-required-flag-is-required
  (testing "all seven, including the two output paths whose absence used to surface as a
            NullPointerException out of `spit` instead of as the usage line"
    (let [complete {:deps-edn "d" :version-edn "v" :group-id "g" :artifact-id "a"
                    :pom-out "p" :properties-out "pr" :coordinates-out "c"}]
      (doseq [missing (keys complete)]
        (is (str/includes? (str (refusal #(pom-gen/write! (dissoc complete missing))))
                           "usage:")
            (str "omitting --" (name missing) " must print the usage"))))))
