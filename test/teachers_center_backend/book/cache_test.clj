(ns teachers-center-backend.book.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [teachers-center-backend.book.cache :as cache])
  (:import [java.io File]))

(deftest the-fingerprint-is-the-files-sha256
  (let [f (doto (File/createTempFile "cache-test" ".pdf") (spit "abc"))]
    (try
      (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (cache/sha256 f)))
      (finally (.delete f)))))

(def ^:private sha (apply str (repeat 64 "a")))

(deftest the-filename-keeps-the-title-and-carries-the-fingerprint
  (is (= (str "Schritte 2 [sha256-" sha "].md") (cache/cached-filename "Schritte 2" sha)))
  (testing "a long title is cut, the fingerprint never"
    (is (re-find #"\[sha256-a{64}\]\.md$" (cache/cached-filename (apply str (repeat 200 "x")) sha)))))

(deftest a-remembered-book-is-found-until-it-expires
  (let [now  (quot (System/currentTimeMillis) 1000)
        sha2 (apply str (repeat 64 "b"))]
    (cache/remember! sha "file-1" (+ now 86400))
    (cache/remember! sha2 "file-2" (+ now 60)) ; expires within the hour — too close to use
    (is (= "file-1" (cache/lookup nil sha)))
    (is (nil? (cache/lookup nil sha2)))
    (testing "a file that turned out to be gone"
      (cache/forget! sha)
      (is (nil? (cache/lookup nil sha))))))
