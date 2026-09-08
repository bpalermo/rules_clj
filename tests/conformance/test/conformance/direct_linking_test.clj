(ns conformance.direct-linking-test
  "What direct linking changes, observed rather than asserted about bytecode.

  The same caller source is compiled by two targets, one with direct_linking
  \"on\" and one \"off\", and each is put on the classpath of its own test target
  (the namespace is the same, so they cannot share one). `with-redefs` on the
  callee is the observable difference: an ordinary fn stops being reachable
  through a direct-linked call site, while a ^:redef fn and a dynamic var stay
  reachable, which is what makes those the escape hatches a library can offer."
  (:require [clojure.test :refer [deftest is testing]]
            [conformance.linked-callee :as callee]
            [conformance.linked-caller :as caller]))

(def direct?
  "Set by the test target through a system property, so one namespace covers both."
  (= "on" (System/getProperty "conformance.direct-linking")))

(deftest ordinary-call
  (testing "the call works either way"
    (is (= :original (caller/call-answer))))
  (testing "redefinition reaches the caller only when it is not direct-linked"
    (with-redefs [callee/answer (constantly :redefined)]
      (is (= (if direct? :original :redefined) (caller/call-answer))))))

(deftest redef-metadata-opts-out
  (testing "^:redef keeps the Var call under direct linking"
    (with-redefs [callee/hook (constantly :redefined)]
      (is (= :redefined (caller/call-hook))))))

(deftest dynamic-vars-are-never-linked
  (testing "a dynamic var is read through its Var either way"
    (binding [callee/*setting* :bound]
      (is (= :bound (caller/call-setting))))))
