(ns teachers-center-backend.book.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [teachers-center-backend.book.jobs :as jobs]))

(defn- wait-for [id pred]
  (loop [n 0]
    (let [job (jobs/get-job id)]
      (if (or (pred job) (> n 100)) job (do (Thread/sleep 20) (recur (inc n)))))))

(deftest a-job-reports-its-progress
  (let [id  (jobs/start-job! {:status "queued" :book-name "B"}
                             (fn [id] (jobs/update-job! id {:status "usable" :vector-store-id "vs_1"})))
        job (wait-for id #(= "usable" (:status %)))]
    (is (= {:status "usable" :vector-store-id "vs_1" :book-name "B"}
           (select-keys job [:status :vector-store-id :book-name])))))

(deftest a-job-that-throws-ends-as-failed
  (let [id  (jobs/start-job! {:status "queued"} (fn [_] (throw (ex-info "Azure analyze failed" {}))))
        job (wait-for id #(= "failed" (:status %)))]
    (is (= "failed" (:status job)))
    (is (= "Azure analyze failed" (:error job)))))

(deftest an-unknown-job-is-nil
  (testing "e.g. after a restart"
    (is (nil? (jobs/get-job "no-such-id")))))
