(ns conformance.jarred-caller
  "Compiled ahead of time, and calls into a namespace whose source arrived in a jar."
  (:require [conformance.jarred :as jarred]))

(defn value [] [:caller (jarred/value)])
