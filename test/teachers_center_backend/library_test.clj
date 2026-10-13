(ns teachers-center-backend.library-test
  "Also makes sure library.clj (the upload flow) compiles — the other tests don't load it."
  (:require [clojure.test :refer [deftest is testing]]
            [teachers-center-backend.library :as library]))

(def ^:private book-title #'library/book-title)
(def ^:private client-ip #'library/client-ip)

(deftest the-title-has-no-pdf-extension
  (is (= "Schritte 2" (book-title "x.pdf" "Schritte 2.pdf")) "the add-in sends the file name")
  (is (= "Schritte 2" (book-title "Schritte 2.PDF" nil)))
  (is (= "Book" (book-title nil "  "))))

(deftest the-ip-comes-from-the-proxy-header
  (is (= "203.0.113.5" (client-ip {:headers {"x-forwarded-for" "203.0.113.5, 10.0.0.1"} :remote-addr "10.0.0.2"})))
  (testing "without a proxy"
    (is (= "10.0.0.2" (client-ip {:headers {} :remote-addr "10.0.0.2"})))))
