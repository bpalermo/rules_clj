(ns store.cart-test
  (:require [clojure.test :refer [deftest is]]
            [store.cart :as cart]))

(deftest adds-normalised-items
  (is (= ["apple"] (cart/add [] "  Apple  ")))
  (is (= 2 (cart/total (cart/add (cart/add [] "a") "b")))))
