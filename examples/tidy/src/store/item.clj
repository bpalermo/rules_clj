(ns store.item
  (:require [clojure.string :as str]))

(defn normalise
  [name]
  (str/trim (str/lower-case (str name))))
