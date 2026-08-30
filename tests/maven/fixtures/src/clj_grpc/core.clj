(ns clj-grpc.core
  "A stand-in for the library's sources.

  The golden test is about the pom, but a jar with nothing in it would not prove that
  the pom lands inside one — so there is exactly one namespace here, and its only job is
  to appear at clj_grpc/core.clj in the artifact.")

(defn version [] :fixture)
