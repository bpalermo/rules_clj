(ns conformance.chain-b
  (:require [conformance.chain-c :as c]))

(defn middle [] [:b (c/deepest)])
