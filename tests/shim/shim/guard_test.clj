(ns shim.guard-test
  "The compiler shim's safety net, driven directly rather than through the rules.

  The two-pass compile keeps a dependency's classes out of a target's jar; the guard in
  Aot.verifyOnlyRequestedNamespaces is what notices when it did not. A guard that never
  fires is indistinguishable from one that cannot fire, so these push it deliberately
  into each failure shape — which a well-formed BUILD file cannot do.

  The shim runs as a subprocess. Everything it needs is already known to this JVM: the
  java binary from java.home, and the shim plus Clojure from this test's own classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- run-shim
  "Runs the shim as a subprocess.

  The classpath appears twice on purpose, and they are different things: -cp is what
  lets the JVM find the shim itself, while --classpath is part of the request and is
  what the shim loads the code under compilation from. Keeping them separate is what
  lets one worker process serve targets whose classpaths differ."
  [{:keys [classpath-prefix args]}]
  (let [java (str (System/getProperty "java.home") File/separator "bin" File/separator "java")
        own (System/getProperty "java.class.path")
        request-classpath (str/join File/pathSeparator (concat classpath-prefix [own]))
        builder (doto (ProcessBuilder. ^java.util.List
                                       (into [java "-cp" own "dev.palermo.rulesclj.Aot"
                                              (str "--classpath=" request-classpath)]
                                             args))
                  (.redirectErrorStream true))
        process (.start builder)
        output (slurp (.getInputStream process))]
    {:exit (.waitFor process) :output output}))

(defn- write-sources!
  "A two-namespace project: root requires leaf."
  [dir]
  (let [pkg (io/file dir "guard")]
    (.mkdirs pkg)
    (spit (io/file pkg "leaf.clj")
          "(ns guard.leaf)\n(defn value [] :leaf)\n")
    (spit (io/file pkg "root.clj")
          "(ns guard.root (:require [guard.leaf :as leaf]))\n(defn value [] [:root (leaf/value)])\n")
    dir))

(defn- jar-entries
  [path]
  (with-open [jar (java.util.jar.JarFile. (io/file path))]
    (->> (enumeration-seq (.entries jar)) (map #(.getName %)) doall set)))

(deftest compiles-only-what-was-asked-for
  (let [src (write-sources! (temp-dir "guard-src"))
        out (str (temp-dir "guard-out") "/root.jar")
        {:keys [exit output]} (run-shim {:classpath-prefix [src]
                                         :args [(str "--output=" out)
                                                "--namespace=guard.root"]})]
    (is (zero? exit) output)
    (let [entries (jar-entries out)]
      (testing "the requested namespace is compiled"
        (is (contains? entries "guard/root__init.class")))
      (testing "its dependency is loaded, not compiled into this jar"
        (is (empty? (filter #(str/includes? % "guard/leaf") entries)))))))

(deftest catches-a-namespace-that-is-already-compiled-elsewhere
  (testing "when a dependency jar already holds the compiled namespace, `load` takes the
            class and emits nothing — the shape of two targets declaring one namespace"
    (let [src (write-sources! (temp-dir "guard-src"))
          classes (temp-dir "guard-classes")
          first-out (str (temp-dir "guard-out") "/root.jar")
          _ (run-shim {:classpath-prefix [src]
                       :args [(str "--output=" first-out)
                              (str "--classes-dir=" classes)
                              "--namespace=guard.root"]})
          ;; Put the compiled classes ahead of the sources, as a dependency jar would be.
          {:keys [exit output]} (run-shim {:classpath-prefix [classes src]
                                           :args [(str "--output=" (temp-dir "guard-out2") "/dup.jar")
                                                  "--namespace=guard.root"]})]
      (is (pos? exit))
      (is (str/includes? output "requested but not compiled"))
      (is (str/includes? output "Each namespace belongs to exactly one target")
          "the error should explain the cause, not just report the symptom"))))

(deftest a-missing-namespace-names-itself
  (let [src (write-sources! (temp-dir "guard-src"))
        {:keys [exit output]} (run-shim {:classpath-prefix [src]
                                         :args [(str "--output=" (temp-dir "guard-out") "/x.jar")
                                                "--namespace=guard.nope"]})]
    (is (pos? exit))
    (is (str/includes? output "compiling guard.nope"))))
