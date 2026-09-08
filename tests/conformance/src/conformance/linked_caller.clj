(ns conformance.linked-caller
  "The caller half. Compiled twice — once with direct_linking = \"on\", once off —
  from the same source, so a test can compare what a redefinition reaches."
  (:require [conformance.linked-callee :as callee]))

(defn call-answer [] (callee/answer))

(defn call-hook [] (callee/hook))

(defn call-setting [] (callee/read-setting))
