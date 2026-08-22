(ns conformance.macros
  "Macros run at the consumer's COMPILE time, so this namespace must be loadable while
  another target is being compiled — not merely present at runtime.")

(defmacro twice
  [expr]
  `(let [v# ~expr] [v# v#]))

(def expansion-marker ::expanded)
