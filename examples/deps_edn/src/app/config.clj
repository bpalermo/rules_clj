(ns app.config
  "Uses a third-party library — data.json — to prove the dependency really is on the
  classpath, rather than the build merely having fetched something."
  (:require [clojure.data.json :as json]))

(defn parse
  [s]
  (json/read-str s :key-fn keyword))

(defn render
  [m]
  (json/write-str m))
