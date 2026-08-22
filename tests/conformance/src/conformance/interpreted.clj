(ns conformance.interpreted
  "Deliberately NOT compiled (aot = False on its target): Clojure loads it from source.
  Mixing compiled and interpreted namespaces has to work, because a project migrating
  to this ruleset will have both.")

(defn answer [] 42)
