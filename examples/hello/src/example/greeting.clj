(ns example.greeting
  "The smallest thing worth testing: a pure function, and a -main that uses it."
  (:require [clojure.string :as str]))

(defn greet
  [who]
  (str "hello, " (str/trim (str who))))

(defn -main
  [& args]
  (println (greet (or (first args) "world"))))
