(ns cli.main
  "A native image has no Clojure compiler in it, so the entry point must be a real class:
  :gen-class, not a -main found by clojure.main at runtime."
  (:gen-class)
  (:require [clojure.string :as str]))

(defn -main
  [& args]
  (println (str/join " " (cons "hello," (or (seq args) ["world"])))))
