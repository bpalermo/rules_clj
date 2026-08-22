(ns example.greeting-test
  (:require [clojure.test :refer [deftest is testing]]
            [example.greeting :as greeting]))

(deftest greet-test
  (testing "the ordinary case"
    (is (= "hello, world" (greeting/greet "world"))))
  (testing "surrounding whitespace is not the caller's problem"
    (is (= "hello, ada" (greeting/greet "  ada  ")))))

(deftest greet-handles-nil
  (is (= "hello, " (greeting/greet nil))))
