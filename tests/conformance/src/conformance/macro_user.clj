(ns conformance.macro-user
  (:require [conformance.macros :as m]))

(defn doubled
  [x]
  (m/twice (inc x)))
