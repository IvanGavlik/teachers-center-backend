(ns teachers-center-backend.book.limits-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [teachers-center-backend.book.limits :as limits]))

(use-fixtures :each (fn [t] (limits/reset-for-tests!) (t)))

(def ^:private cfg {:daily-pages 500 :ip-daily-books 2})

(deftest books-are-counted-until-a-limit-is-full
  (testing "per IP"
    (is (nil? (limits/try-reserve! "1.1.1.1" 10 cfg)))
    (is (nil? (limits/try-reserve! "1.1.1.1" 10 cfg)))
    (is (= :ip-books (limits/try-reserve! "1.1.1.1" 10 cfg)))
    (is (nil? (limits/try-reserve! "2.2.2.2" 10 cfg)) "another IP still can"))
  (testing "for the whole service"
    (is (= :daily-pages (limits/try-reserve! "3.3.3.3" 480 cfg)) "30 + 480 > 500")
    (is (nil? (limits/try-reserve! "3.3.3.3" 470 cfg)) "30 + 470 = 500 still fits")
    (is (= {:pages 500 :ips 3} (select-keys (limits/snapshot) [:pages :ips])))))

(deftest a-refused-book-isnt-counted
  (is (= :daily-pages (limits/try-reserve! "1.1.1.1" 600 cfg)))
  (is (= 0 (:pages (limits/snapshot)))))
