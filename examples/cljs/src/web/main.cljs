(ns web.main
  (:require [clojure.string :as str]))

(defn greeting
  [who]
  (str/join " " ["hello," (str/trim (str who))]))

(defn ^:export run
  []
  (js/console.log (greeting "world")))
