(ns maven.publisher-test
  "The publisher, driven in-process over the artifacts the rule actually built.

  Two things are worth proving about a tool that uploads immutable releases, and
  neither is provable by reading it:

  1. `--dry-run` prints what the real run would do. Not a description of it — the same
     list, produced by the same planning pass. A dry run that is a separate code path
     is a dry run that reassures you about code that will not execute.

  2. The Maven layout is right, down to the checksum bytes. `file:` publishing exists
     precisely so this can be checked without a network or a credential: the same
     planner, the same paths, a temp directory instead of Clojars.

  `Publisher.run` takes its streams as arguments and returns an exit code rather than
  calling `System/exit`, which is what lets all of this be a function call instead of a
  subprocess whose output has to be scraped."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [dev.palermo.rulesclj.maven Publisher]
           [java.io ByteArrayOutputStream PrintStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

;; --- fixtures ---------------------------------------------------------------

(defn- runfile
  "The rootpath in `variable`, as a readable File. See maven.pom-gen-test for why the
  direct path is tried before TEST_SRCDIR."
  [variable]
  (let [path (or (System/getenv variable)
                 (throw (ex-info (str variable " is not set") {:variable variable})))
        direct (io/file path)]
    (if (.isFile direct)
      direct
      (let [via-srcdir (io/file (System/getenv "TEST_SRCDIR")
                                (System/getenv "TEST_WORKSPACE")
                                path)]
        (when-not (.isFile via-srcdir)
          (throw (ex-info (str variable " names no file: " path) {:path path})))
        via-srcdir))))

(defn- temp-dir
  [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- publish
  "Runs the publisher and returns its exit code and both streams."
  [& args]
  (let [out (ByteArrayOutputStream.)
        err (ByteArrayOutputStream.)
        exit (Publisher/run (into-array String args)
                            (PrintStream. out true "UTF-8")
                            (PrintStream. err true "UTF-8"))]
    {:exit exit
     :out (.toString out "UTF-8")
     :err (.toString err "UTF-8")}))

(defn- artifact-args
  [repository]
  [(str "--repository=" repository)
   (str "--coordinates=" (runfile "GENERATED_COORDINATES"))
   (str "--pom=" (runfile "GENERATED_POM"))
   (str "--jar=" (runfile "GENERATED_JAR"))])

(def ^:private path
  "Where com.github.bpalermo:clj-grpc:0.1.4 goes in any Maven repository."
  "com/github/bpalermo/clj-grpc/0.1.4")

(def ^:private extensions
  "The five files per artifact, in the order the publisher plans them."
  ["" ".md5" ".sha1" ".sha256" ".sha512"])

;; --- dry run ----------------------------------------------------------------

(deftest dry-run-names-every-file-and-sends-none
  (let [{:keys [exit out err]} (apply publish "--dry-run"
                                      (artifact-args "https://clojars.org/repo"))]
    (is (zero? exit) err)
    (testing "the jar, the pom, and four checksum siblings for each"
      (doseq [artifact ["clj-grpc-0.1.4.jar" "clj-grpc-0.1.4.pom"]
              extension extensions]
        (let [url (str "https://clojars.org/repo/" path "/" artifact extension)]
          (is (str/includes? out (str "PUT " url " ")) (str "missing " url)))))
    (testing "ten files and no more — a count is what catches an extra artifact"
      (is (= 10 (count (filter #(str/starts-with? % "PUT ") (str/split-lines out))))))
    (testing "and it says plainly that it sent nothing"
      (is (str/includes? out "10 files would be uploaded; nothing was sent.")))))

(deftest the-jar-is-planned-before-the-pom
  (testing "a repository that indexes on seeing a pom must not see one whose jar is
            missing: a half-published version is not something Clojars lets you undo"
    (let [{:keys [out]} (apply publish "--dry-run"
                               (artifact-args "https://clojars.org/repo"))
          lines (filter #(str/starts-with? % "PUT ") (str/split-lines out))
          index (fn [suffix] (first (keep-indexed #(when (str/includes? %2 suffix) %1) lines)))]
      (is (< (index "clj-grpc-0.1.4.jar ") (index "clj-grpc-0.1.4.pom "))))))

;; --- a real publish, into a file: repository --------------------------------

(defn- hex-digest
  [algorithm ^java.io.File file]
  (let [digest (.digest (MessageDigest/getInstance algorithm) (Files/readAllBytes (.toPath file)))]
    (apply str (map #(format "%02x" %) digest))))

(deftest file-repository-install-writes-the-maven-layout
  (let [root (temp-dir "publisher-repo")
        {:keys [exit out err]} (apply publish (artifact-args (str "file://" root)))
        directory (io/file root path)]
    (is (zero? exit) (str out err))
    (is (str/includes? out (str "installed 10 files into " root)))

    (testing "the artifacts land at {group as path}/{artifact}/{version}/"
      (doseq [artifact ["clj-grpc-0.1.4.jar" "clj-grpc-0.1.4.pom"]
              extension extensions]
        (is (.isFile (io/file directory (str artifact extension)))
            (str "missing " artifact extension))))

    (testing "the installed files are the built ones, byte for byte"
      (is (= (seq (Files/readAllBytes (.toPath (runfile "GENERATED_POM"))))
             (seq (Files/readAllBytes (.toPath (io/file directory "clj-grpc-0.1.4.pom"))))))
      (is (= (seq (Files/readAllBytes (.toPath (runfile "GENERATED_JAR"))))
             (seq (Files/readAllBytes (.toPath (io/file directory "clj-grpc-0.1.4.jar")))))))

    (testing "and each checksum file holds the bare lowercase hex digest of its
              artifact — computed here rather than compared against a recorded string,
              so the test does not agree with the publisher by construction"
      (doseq [artifact ["clj-grpc-0.1.4.jar" "clj-grpc-0.1.4.pom"]
              [extension algorithm] [[".md5" "MD5"]
                                     [".sha1" "SHA-1"]
                                     [".sha256" "SHA-256"]
                                     [".sha512" "SHA-512"]]]
        (is (= (hex-digest algorithm (io/file directory artifact))
               (slurp (io/file directory (str artifact extension))))
            (str artifact extension))))))

;; A `file:` repository is the one argument whose value is a filesystem path, and
;; filesystem paths contain spaces — ~/Library/Application Support, or any Windows-shaped
;; habit brought to a mac. Both spellings of such a path have to work, and have to agree.

(defn- repository-with-a-space
  "A directory whose name contains a space, inside a fresh temp directory."
  []
  (doto (io/file (temp-dir "publisher-repo") "my repository") (.mkdirs)))

(deftest a-file-repository-path-may-contain-a-space
  (testing "the literal spelling, which is what a person types and is not a legal URI"
    (let [root (repository-with-a-space)
          {:keys [exit out err]} (apply publish (artifact-args (str "file://" root)))]
      (is (zero? exit) (str out err))
      (is (.isFile (io/file root path "clj-grpc-0.1.4.jar")))
      (is (.isFile (io/file root path "clj-grpc-0.1.4.pom.sha256"))))))

(deftest a-percent-encoded-file-repository-names-the-same-directory
  (testing "the well-formed spelling of the same path — the two readings must agree, which
            is the property that lets one fall back to the other"
    (let [root (repository-with-a-space)
          encoded (str "file://" (str/replace (str root) " " "%20"))
          {:keys [exit out err]} (apply publish (artifact-args encoded))]
      (is (zero? exit) (str out err))
      (is (.isFile (io/file root path "clj-grpc-0.1.4.jar"))
          "a %20 in the URL must land in the directory whose name has a space"))))

;; --- a dry run that passes must mean something ------------------------------
;;
;; The whole value of --dry-run is that it is the same code path with the sending taken
;; out. A check that lived in the upload step rather than in the planning step would let
;; a rehearsal report success for a repository the real run cannot use — and this is the
;; command people run precisely because the real one cannot be taken back.

(def ^:private unusable-repositories
  [["file:relative/repo" "must be an absolute path"]
   ["file://elsewhere/repo" "cannot name another host"]
   ["clojars.org/repo" "needs a scheme"]
   ["ftp://clojars.org/repo" "must be http, https or file:"]
   ["https://clojars .org/repo" "not a usable repository URL"]])

(deftest an-unusable-repository-is-refused
  (doseq [[repository fragment] unusable-repositories]
    (testing repository
      (let [{:keys [exit err]} (apply publish (artifact-args repository))]
        (is (= 1 exit))
        (is (str/includes? err fragment) err)))))

(deftest a-dry-run-fails-wherever-the-real-run-would
  (doseq [[repository fragment] unusable-repositories]
    (testing (str repository " under --dry-run")
      (let [dry (apply publish "--dry-run" (artifact-args repository))
            real (apply publish (artifact-args repository))]
        (is (= 1 (:exit dry))
            (str "--dry-run reported success for a repository the real run refuses:\n"
                 (:out dry)))
        (is (= (:exit real) (:exit dry)) "the two runs must agree")
        (is (str/includes? (:err dry) fragment) (:err dry))))))

(deftest coordinates-that-cannot-make-a-url-are-caught-while-planning
  (testing "the URLs are built from a coordinates file, so a legal repository does not
            make them legal — and the alternative to catching it here is URI.create
            throwing at the moment of the first PUT, half way through a release"
    (let [coordinates (io/file (temp-dir "publisher-coords") "coordinates.txt")
          _ (spit coordinates "com.example:lib:1.0 SNAPSHOT\n")
          {:keys [exit err]} (publish "--repository=https://clojars.org/repo"
                                      (str "--coordinates=" coordinates)
                                      (str "--pom=" (runfile "GENERATED_POM"))
                                      (str "--jar=" (runfile "GENERATED_JAR"))
                                      "--dry-run")]
      (is (= 1 exit))
      (is (str/includes? err "do not make a usable URL") err))))

(deftest a-missing-artifact-is-reported-before-anything-is-sent
  (let [{:keys [exit err]} (publish "--repository=https://clojars.org/repo"
                                    (str "--coordinates=" (runfile "GENERATED_COORDINATES"))
                                    (str "--pom=" (runfile "GENERATED_POM"))
                                    "--jar=/nonexistent/clj-grpc.jar"
                                    "--dry-run")]
    (is (= 1 exit))
    (is (str/includes? err "not a file: /nonexistent/clj-grpc.jar"))))

(deftest an-unusable-command-line-prints-the-usage
  (let [{:keys [exit err]} (publish "--pom=/dev/null")]
    (is (= 2 exit))
    (is (str/includes? err "--coordinates is required"))
    (is (str/includes? err "usage: Publisher"))))
