(ns app.config-test
  (:require [app.config :as config]
            [clojure.test :refer [deftest is]]))

(deftest round-trips-json
  (is (= {:a 1 :b "two"} (config/parse "{\"a\":1,\"b\":\"two\"}")))
  (is (= "{\"a\":1}" (config/render {:a 1}))))
