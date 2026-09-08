(ns conformance.linked-callee
  "The callee half of the direct-linking fixture: one ordinary fn, one marked
  ^:redef, and one dynamic var. Direct linking is meant to skip the Var for the
  first and keep it for the other two.")

(defn answer
  "An ordinary fn. A direct-linked caller keeps calling this definition."
  []
  :original)

(defn ^:redef hook
  "Marked ^:redef, so the compiler emits a Var call even under direct linking."
  []
  :original)

(def ^:dynamic *setting*
  "Dynamic vars are never direct-linked."
  :original)

(defn read-setting [] *setting*)
