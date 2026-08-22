(ns rules-clj.runner
  "Runs clojure.test over one or more namespaces and reports the result twice:
  to the console for a human, and as JUnit XML for the build system.

  Bazel decides pass or fail from the exit code alone, which reduces a suite of
  two hundred assertions to one bit. It also sets XML_OUTPUT_FILE for every test
  action, and anything written there is parsed back into per-test results — so
  filling it in is the difference between `//foo:bar FAILED` and knowing which
  deftest failed and why.

  Only clojure.core and clojure.test are used here. This namespace is loaded into
  the same JVM as the code under test, and a test runner that drags in a library
  is a test runner that can break a build by disagreeing with it."
  (:require [clojure.string :as str]
            [clojure.test :as test]))

;; Accumulated as the run proceeds: one map per deftest, in completion order.
(def ^:private results (atom {:cases [], :current nil}))

(defn- current-or-synthetic
  "clojure.test does not pair every event with a test var — an error thrown in a
  fixture arrives with no :begin-test-var before it. Rather than drop those, give
  them somewhere to land."
  [state]
  (or (:current state)
      {:name "unknown", :classname "unknown", :start (System/nanoTime),
       :failures [], :errors []}))

(defn- describe
  "A human-readable account of one failure or error event."
  [m]
  (let [contexts (seq (test/testing-contexts-str))]
    (str/join "\n"
              (remove nil?
                      [(when contexts (str/trim (test/testing-contexts-str)))
                       (when-let [msg (:message m)] msg)
                       (str "expected: " (pr-str (:expected m)))
                       (str "  actual: " (pr-str (:actual m)))
                       (when-let [f (:file m)] (str "      at: " f ":" (:line m)))]))))

(defn- record-event!
  [m]
  (case (:type m)
    :begin-test-var
    (let [meta' (meta (:var m))]
      (swap! results assoc :current {:name (str (:name meta'))
                                     :classname (str (ns-name (:ns meta')))
                                     :start (System/nanoTime)
                                     :failures []
                                     :errors []}))

    :end-test-var
    (swap! results
           (fn [state]
             (let [case' (current-or-synthetic state)]
               (-> state
                   (update :cases conj
                           (-> case'
                               (assoc :time (/ (double (- (System/nanoTime) (:start case')))
                                               1e9))
                               (dissoc :start)))
                   (assoc :current nil)))))

    :fail
    (swap! results (fn [state]
                     (assoc state :current
                            (update (current-or-synthetic state) :failures conj (describe m)))))

    :error
    (swap! results (fn [state]
                     (assoc state :current
                            (update (current-or-synthetic state) :errors conj (describe m)))))

    nil)
  m)

(defn- xml-escape
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      ;; Control characters are not representable in XML 1.0 at all, and a stray
      ;; one in an assertion message would make the whole report unparseable.
      (str/replace #"[\x00-\x08\x0b\x0c\x0e-\x1f]" "")))

(defn- case->xml
  [{:keys [name classname time failures errors]}]
  (str "    <testcase name=\"" (xml-escape name) "\""
       " classname=\"" (xml-escape classname) "\""
       " time=\"" (format "%.3f" (or time 0.0)) "\""
       (if (and (empty? failures) (empty? errors))
         "/>\n"
         (str ">\n"
              (str/join (for [f failures]
                          (str "      <failure>" (xml-escape f) "</failure>\n")))
              (str/join (for [e errors]
                          (str "      <error>" (xml-escape e) "</error>\n")))
              "    </testcase>\n"))))

(defn- report-xml
  [suite-name cases]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<testsuites>\n"
       "  <testsuite name=\"" (xml-escape suite-name) "\""
       " tests=\"" (count cases) "\""
       " failures=\"" (count (filter (comp seq :failures) cases)) "\""
       " errors=\"" (count (filter (comp seq :errors) cases)) "\">\n"
       (str/join (map case->xml cases))
       "  </testsuite>\n"
       "</testsuites>\n"))

(defn -main
  [& namespaces]
  (when (empty? namespaces)
    (binding [*out* *err*]
      (println "usage: rules-clj.runner <namespace>..."))
    (System/exit 2))

  (let [syms (map symbol namespaces)
        delegate test/report
        summary (do (apply require syms)
                    (with-redefs [test/report (comp delegate record-event!)]
                      (apply test/run-tests syms)))]

    (when-let [path (System/getenv "XML_OUTPUT_FILE")]
      (spit path (report-xml (str/join " " namespaces) (:cases @results))))

    ;; Agents keep non-daemon threads alive; without this a passing suite can hang
    ;; for up to a minute before the JVM decides to exit.
    (shutdown-agents)
    (System/exit (if (or (pos? (:fail summary 0)) (pos? (:error summary 0))) 1 0))))
