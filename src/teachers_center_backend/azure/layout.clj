(ns teachers-center-backend.azure.layout
  "Azure Document Intelligence, prebuilt-layout with Markdown output: OCR for scanned books
   (doc/book-ocr-investigation.md step ①). One call for the whole PDF; Azure works asynchronously,
   so we submit and then poll the operation until it's done."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]))

(def ^:private poll-interval-ms 3000)

(def ^:private default-timeout-ms
  "A 211-page scan took 23 s on S0; this leaves room for a slow day without hanging a job forever."
  (* 10 60 1000))

(defn configured? [{:keys [endpoint key]}]
  (and (not (str/blank? endpoint)) (not (str/blank? key))))

(defn- poll-operation [{:keys [key]} op-url timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [res  (http/get op-url {:headers          {"Ocp-Apim-Subscription-Key" key}
                                   :throw-exceptions false})
            body (json/parse-string (:body res) true)]
        (case (:status body)
          "succeeded" body
          "failed"    (throw (ex-info "Azure analyze failed" {:error (:error body)}))
          (if (> (System/currentTimeMillis) deadline)
            (throw (ex-info "Azure analyze timed out" {:status (:status body)}))
            (do (Thread/sleep poll-interval-ms) (recur))))))))

(defn analyze
  "Runs prebuilt-layout on a PDF and returns Azure's :analyzeResult (:content as Markdown, :pages,
   :styles). Throws on a rejected request, a failed analysis or a timeout."
  ([azure ^File pdf] (analyze azure pdf {}))
  ([{:keys [endpoint key] :as azure} ^File pdf {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
   (when-not (configured? azure)
     (throw (ex-info "Azure Document Intelligence is not configured (AZURE_DI_ENDPOINT / AZURE_DI_KEY)" {})))
   (let [url (str (str/replace endpoint #"/+$" "")
                  "/documentintelligence/documentModels/prebuilt-layout:analyze"
                  ;; offsets in UTF-16 units, like Java strings: convert/split-pages cuts pages and
                  ;; handwriting out of :content with them (Azure's default counts graphemes)
                  "?api-version=2024-11-30&outputContentFormat=markdown&stringIndexType=utf16CodeUnit")
         t0  (System/currentTimeMillis)
         res (http/post url {:headers            {"Ocp-Apim-Subscription-Key" key
                                                  "Content-Type"              "application/pdf"}
                             :body               pdf
                             :throw-exceptions   false
                             :socket-timeout     600000
                             :connection-timeout 60000})]
     (when-not (= 202 (:status res))
       (throw (ex-info "Azure analyze request rejected" {:status (:status res) :body (:body res)})))
     (let [result (:analyzeResult (poll-operation azure (get-in res [:headers "operation-location"]) timeout-ms))]
       (log/info "Azure layout done" {:pages (count (:pages result)) :ms (- (System/currentTimeMillis) t0)})
       result))))
