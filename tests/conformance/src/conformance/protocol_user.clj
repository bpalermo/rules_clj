(ns conformance.protocol-user
  "Extends and consumes the protocol from a DIFFERENT target, so the interface
  crosses a compilation boundary the way it would in a real project."
  (:require [conformance.protocols :as p]))

(defrecord Casual []
  p/Greeter
  (greet [_ who] (str "hi " who)))

(defn greet-both
  [who]
  [(p/greet (p/->Formal) who) (p/greet (->Casual) who)])

(defn formal-satisfies?
  []
  (satisfies? p/Greeter (p/->Formal)))
