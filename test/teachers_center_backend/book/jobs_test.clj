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

(deftest updating-a-dropped-job-does-nothing
  (jobs/update-job! "gone" {:status "ready"})
  (is (nil? (jobs/get-job "gone"))))

(deftest a-second-upload-finds-the-job-for-its-pdf
  (let [slow #(Thread/sleep 300)
        id   (jobs/start-job! {:status "queued" :sha "sha-live"} (fn [_] (slow)))]
    (is (= id (:id (jobs/live-job-for "sha-live"))))
    (is (nil? (jobs/live-job-for "sha-other")))
    (testing "not a failed job — a new upload should try again"
      (let [failed (jobs/start-job! {:status "queued" :sha "sha-failed"} (fn [_] (throw (ex-info "x" {}))))]
        (wait-for failed #(= "failed" (:status %)))
        (is (nil? (jobs/live-job-for "sha-failed")))))
    (testing "not a book with pages Azure couldn't read — a new upload should read them again"
      (jobs/update-job! id {:ocr-missing 20})
      (is (nil? (jobs/live-job-for "sha-live"))))))
