(ns conformance.source-jar-test
  "Compiling a library that ships as source, so a linked caller can call it.

  Direct linking emits the callee's class name, which exists only if the callee was
  compiled ahead of time. Nearly every Clojure library on Maven ships as source, so
  without this the attribute is unusable against them — the compiler shim refuses the
  build, correctly, rather than leaving a NoClassDefFoundError for the first call.

  The way out is to compile the jar's namespaces in the consumer's own build: a
  `clj_library` with no `srcs`, whose sources come from the jar on its classpath. The
  fixture here packages one namespace as source exactly as Clojars would, compiles it,
  and links a caller against the result."
  (:require [clojure.test :refer [deftest is testing]]
            [conformance.jarred :as jarred]
            [conformance.jarred-caller :as caller]))

(deftest compiled-out-of-a-source-jar
  (testing "the namespace loads from the compiled classes"
    (is (= :jarred (jarred/value))))
  (testing "a caller compiled with direct linking reaches it"
    (is (= [:caller :jarred] (caller/value)))))

(deftest the-classes-are-what-loaded
  (testing "compiling put a class file on the classpath, and the loader took it over
            the source in the jar — Jars stamps classes later than sources so the
            class wins even when both are present"
    (is (some? (.getResource (.getContextClassLoader (Thread/currentThread))
                             "conformance/jarred__init.class")))))

(deftest the-call-is-linked
  (testing "redefinition does not reach a direct-linked call site, which is the whole
            point of compiling the jar in the first place"
    (with-redefs [jarred/value (constantly :redefined)]
      (is (= [:caller :jarred] (caller/value))))))
