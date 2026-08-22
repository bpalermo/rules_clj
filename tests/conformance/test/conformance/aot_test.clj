(ns conformance.aot-test
  "What ahead-of-time compilation has to get right, asserted rather than assumed.

  Each case here corresponds to a way non-transitive compilation can go wrong, and
  every one of them fails at RUNTIME in a consumer's project rather than at build time
  in ours — which is exactly why they are tests."
  (:require [clojure.test :refer [deftest is testing]]
            [conformance.chain-a :as chain]
            [conformance.interpreted :as interpreted]
            [conformance.macro-user :as macro-user]
            [conformance.protocol-user :as protocol-user]
            [conformance.protocols :as protocols]
            [conformance.reader-user :as reader-user]
            [conformance.type-user :as type-user]
            [conformance.types :as types])
  (:import [conformance.types Box]))

(deftest protocols-survive-a-compilation-boundary
  (testing "a record from the defining target satisfies the protocol"
    (is (protocol-user/formal-satisfies?)))
  (testing "an implementation from another target dispatches correctly"
    (is (= ["Good day, ada" "hi ada"] (protocol-user/greet-both "ada"))))
  (testing "extending in a third place still resolves to one interface"
    (extend-protocol protocols/Greeter
      String
      (greet [this who] (str this " " who)))
    (is (= "oi ada" (protocols/greet "oi" "ada")))))

(deftest deftype-classes-are-packaged-and-identical
  (testing "the class is in the jar at all — it is named after the type, not the ns"
    (is (= 7 (type-user/round-trip 7))))
  (testing "class identity holds across targets"
    (is (type-user/is-a-box? (types/box 1)))
    (is (instance? Box (types/box 1)))))

(deftest macros-are-available-at-a-consumers-compile-time
  (is (= [2 2] (macro-user/doubled 1))))

(deftest transitive-requires-load-without-being-recompiled
  (is (= [:a [:b :c]] (chain/top))))

(deftest data-readers-are-visible-while-compiling
  (is (= "QUIET" reader-user/shouted)))

(deftest compiled-and-interpreted-namespaces-interoperate
  (is (= 42 (interpreted/answer))))
