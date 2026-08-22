(ns conformance.type-user
  "Uses the type across a target boundary, where class identity has to hold."
  (:require [conformance.types :as t])
  (:import [conformance.types Box]))

(defn round-trip
  [v]
  @(t/box v))

(defn is-a-box?
  [v]
  (instance? Box v))
