(ns rules-clj.lock
  "Turns a deps.edn into a lockfile that Bazel can fetch from.

  Resolution is the one part of dependency handling worth delegating: version conflicts,
  exclusions, POM parents and classifiers are a decade of accumulated rules, and
  tools.deps already implements them exactly as the `clj` command does. What this adds is
  the part tools.deps does not do — writing down what it decided, with digests, so that a
  build can fetch the same jars without resolving anything and without a network.

  Run through Bazel (`bazel run //tools/lock -- ...`) or, when bootstrapping this tool's
  own dependencies, through the Clojure CLI. It is the same program either way."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.deps :as deps])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def ^:private default-repositories
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(defn- sha256
  [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream file)]
      (let [buffer (byte-array 65536)]
        (loop []
          (let [n (.read in buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur))))))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn target-name
  "The Bazel target for a library, following the convention rules_jvm_external set.

  Not an arbitrary choice: people move between JVM rulesets, and a familiar label saves
  them translating one mental model into another. A classifier's `$` separator
  (io.netty/netty-transport-native-epoll$linux-x86_64) munges to `_` like the
  rest — `$` is not legal in a Bazel target name."
  [lib]
  (-> (str (namespace lib) "_" (name lib))
      (str/replace #"[.\-$]" "_")))

(defn- artifact-path
  "The repository-relative path of a jar, derived from its coordinates.

  Taken from the coordinates rather than from wherever tools.deps cached it: the local
  path is one machine's accident, while the coordinates are what every Maven mirror
  agrees on.

  A classified lib (tools.deps spells it artifact$classifier) lives at the
  UNclassified artifact's directory with the classifier as a filename suffix:
  netty-transport-native-epoll$linux-x86_64 4.2.16.Final is
  io/netty/netty-transport-native-epoll/4.2.16.Final/
  netty-transport-native-epoll-4.2.16.Final-linux-x86_64.jar. Deriving the
  path from the lib name verbatim produced a URL no Maven repository serves."
  [lib version]
  (let [[artifact classifier] (str/split (name lib) #"\$" 2)]
    (str (str/replace (namespace lib) "." "/") "/"
         artifact "/" version "/"
         artifact "-" version
         (when classifier (str "-" classifier))
         ".jar")))

(defn- repository-urls
  [deps-edn]
  (->> (merge default-repositories (:mvn/repos deps-edn))
       vals
       (keep :url)
       (mapv #(if (str/ends-with? % "/") % (str % "/")))))

(defn- json-escape
  [s]
  (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))

(defn- json
  "A tiny writer, because this program runs on a classpath that should stay small and the
  shape being written is fixed."
  [value indent]
  (let [pad (apply str (repeat indent "  "))
        pad+ (str pad "  ")]
    (cond
      (map? value)
      (if (empty? value)
        "{}"
        (str "{\n"
             (str/join ",\n"
                       (for [[k v] value]
                         (str pad+ "\"" (json-escape (if (keyword? k) (name k) k)) "\": "
                              (json v (inc indent)))))
             "\n" pad "}"))

      (sequential? value)
      (if (empty? value)
        "[]"
        (str "[\n"
             (str/join ",\n" (for [v value] (str pad+ (json v (inc indent)))))
             "\n" pad "]"))

      (string? value) (str "\"" (json-escape value) "\"")
      (number? value) (str value)
      (boolean? value) (str value)
      (nil? value) "null"
      :else (str "\"" (json-escape value) "\""))))

(defn lock
  "Resolves deps.edn and returns the lock as data."
  [{:keys [deps-edn aliases]}]
  (let [edn (deps/slurp-deps (io/file deps-edn))
        ;; :user nil is what makes a lock portable, and leaving it out is a trap. By
        ;; default create-basis merges ~/.clojure/deps.edn, so whoever regenerates the
        ;; lock quietly writes their personal tooling into the project's dependencies —
        ;; the first run of this produced a lock carrying nrepl, OpenTelemetry and the
        ;; Kotlin stdlib for a project that asked for data.json. The equivalent of
        ;; `clj -Srepro`.
        basis (deps/create-basis {:project (str deps-edn)
                                  :user nil
                                  :aliases (mapv keyword aliases)})
        urls (repository-urls edn)
        libs (->> (:libs basis)
                  (filter (fn [[_ coord]] (:mvn/version coord)))
                  (sort-by key))
        ;; tools.deps records :dependents — who depends on this — which is the reverse of
        ;; what a Bazel target needs. Inverting it once here is cheaper than every consumer
        ;; of the lock working it out, and getting the direction wrong is invisible until a
        ;; classpath is missing something at runtime.
        forward (reduce (fn [acc [lib coord]]
                          (reduce (fn [acc dependent] (update acc dependent (fnil conj #{}) lib))
                                  acc
                                  (:dependents coord)))
                        {}
                        libs)]
    {:version 1
     :repositories urls
     :artifacts
     (vec
      (for [[lib coord] libs
            :let [path (artifact-path lib (:mvn/version coord))
                  jar (first (filter #(str/ends-with? % ".jar") (:paths coord)))]]
        (do
          (when-not (and jar (.exists (io/file jar)))
            (throw (ex-info "resolved library has no jar on disk"
                            {:lib lib :coord coord})))
          {:coordinates (str lib ":" (:mvn/version coord))
           :target (target-name lib)
           :path path
           :sha256 (sha256 jar)
           ;; The graph, so a consumer can depend on one library and get what it needs
           ;; rather than on everything.
           :deps (vec (sort (map target-name (get forward lib))))})))}))

(defn -main
  [& args]
  (let [opts (into {} (for [arg args
                            :let [[_ k v] (re-matches #"--([^=]+)=(.*)" arg)]
                            :when k]
                        [(keyword k) v]))
        aliases (if-let [a (:aliases opts)] (str/split a #",") [])
        {:keys [deps-edn output]} opts]
    (when-not (and deps-edn output)
      (binding [*out* *err*]
        (println "usage: --deps-edn=PATH --output=PATH [--aliases=a,b]"))
      (System/exit 2))
    (let [result (lock {:deps-edn deps-edn :aliases aliases})]
      (spit output (str (json result 0) "\n"))
      (println (format "wrote %s: %d artifacts"
                       output (count (:artifacts result)))))
    (shutdown-agents)))
