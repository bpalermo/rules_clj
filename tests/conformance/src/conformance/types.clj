(ns conformance.types
  "deftype produces a class named after the TYPE, not the namespace — conformance/Box
  rather than conformance/types$something. A packaging rule that filtered classfiles by
  namespace prefix would drop it, and the failure would appear at runtime.")

(deftype Box [value]
  clojure.lang.IDeref
  (deref [_] value))

(defn box [v] (->Box v))
