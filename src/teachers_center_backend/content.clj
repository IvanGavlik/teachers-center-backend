(ns teachers-center-backend.content
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- replace-placeholders [text params]
  "Replace {{placeholder}} patterns in text with values from params map"
  (reduce (fn [acc [k v]]
            (str/replace acc (re-pattern (str "\\{\\{" (name k) "\\}\\}")) (fn [_] (str v))))
          text
          params))

(defn render-content [content params]
  "Recursively render content structure, replacing placeholders only in string values"
  (walk/postwalk
    (fn [x]
      (if (string? x)
        (replace-placeholders x params)
        x))
    content))
