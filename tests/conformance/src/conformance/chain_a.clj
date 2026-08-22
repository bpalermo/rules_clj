(ns conformance.chain-a
  "Depends on chain-b, which depends on chain-c. Compiling this must load both without
  compiling either into this target's jar."
  (:require [conformance.chain-b :as b]))

(defn top [] [:a (b/middle)])
