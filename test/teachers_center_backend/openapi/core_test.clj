(ns teachers-center-backend.openapi.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [teachers-center-backend.openapi.core :as openai]))

(def response-with-search
  {:output [{:type    "file_search_call"
             :queries ["sorption vocabulary"]
             :results [{:filename "book.pdf" :score 0.41 :text "low score"}
                       {:filename "book.pdf" :score 0.9123 :text (apply str (repeat 200 "x"))}]}
            {:type "message" :content [{:type "output_text" :text "{}" :annotations []}]}]})

(deftest file-search-summary
  (testing "search with included results"
    (let [s (openai/file-search-summary response-with-search)]
      (is (true? (:searched? s)))
      (is (= ["sorption vocabulary"] (:queries s)))
      (is (= 2 (:result-count s)))
      (is (= [0.91 0.41] (mapv :score (:top-results s))) "highest score first, rounded")
      (is (= 120 (count (:text (first (:top-results s))))) "passage text truncated")
      (is (zero? (:citations s)))))
  (testing "no search call"
    (is (= {:searched? false :queries [] :result-count 0 :top-results [] :citations 0}
           (openai/file-search-summary {:output [{:type "message" :content [{:type "output_text" :text "{}"}]}]}))))
  (testing "results not included (null)"
    (is (= 0 (:result-count (openai/file-search-summary
                              {:output [{:type "file_search_call" :queries ["q"] :results nil}]}))))))

(deftest response-output-text-failures
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incomplete"
                        (openai/response-output-text {:status "incomplete" :output []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused"
                        (openai/response-output-text
                          {:status "completed"
                           :output [{:type "message" :content [{:type "refusal" :refusal "no"}]}]}))))
