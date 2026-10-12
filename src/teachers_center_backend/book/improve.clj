(ns teachers-center-backend.book.improve
  "Steps ③–⑤ of doc/book-ocr-investigation.md (doc/book-production-plan.md, Phase 2): a vision model
   repairs the layout of each page from its image and Azure's text, the check makes sure it didn't
   change the words, one retry with the check's reasons, and Azure's text as the fallback.

   Runs in the background after the book is already usable with Azure's text."
  (:require [teachers-center-backend.openapi.core :as openai]
            [teachers-center-backend.book.check :as check]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io ByteArrayOutputStream File]
           [java.util Base64]
           [java.util.concurrent Executors Semaphore ThreadFactory TimeUnit]
           [javax.imageio ImageIO]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.graphics.image PDImageXObject]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]))

(def ^:private max-model-calls
  "Model calls at once across all books, so two uploads together don't run into OpenAI's rate
   limits. 8 at once for one book used ~1/4 of the account's limits on the 211-page run."
  (Semaphore. 16 true))

;; ── ③ Page image ─────────────────────────────────────────────────────────────

(defn- page-images [^PDDocument doc idx]
  (let [res (.getResources (.getPage doc idx))]
    (for [n (.getXObjectNames res)
          :let [x (.getXObject res n)]
          :when (instance? PDImageXObject x)]
      x)))

(defn page-image-url
  "A scanned page is one big embedded image — use it as it is; otherwise render at 150 dpi.
   PDFBox documents aren't thread-safe, so callers lock the document."
  [^PDDocument doc idx]
  (let [[img & more] (page-images doc idx)
        bi  (if (and img (empty? more) (>= (.getWidth ^PDImageXObject img) 1000))
              (.getImage ^PDImageXObject img)
              (.renderImageWithDPI (PDFRenderer. doc) (int idx) 150 ImageType/RGB))
        out (ByteArrayOutputStream.)]
    (ImageIO/write bi "jpg" out)
    (str "data:image/jpeg;base64," (.encodeToString (Base64/getEncoder) (.toByteArray out)))))

;; ── ④ Vision model repair ────────────────────────────────────────────────────

(defn load-prompt []
  ;; The rules are the instructions; the OCR text goes with the image in the message.
  (str/trim (str/replace (slurp (io/resource "book/page-prompt.txt")) #"OCR TEXT:\s*$" "")))

(defn- strip-fences [s]
  (-> s (str/replace #"^\s*```(?:markdown|md)?\s*\n" "") (str/replace #"\n```\s*$" "") str/trim))

(defn repair-page [client {:keys [model effort prompt]} azure-text image-url feedback]
  (let [t0  (System/nanoTime)
        res (openai/responses-api
              client
              {:instructions prompt
               :input        [{:role    "user"
                               :content [{:type "input_text"
                                          :text (cond-> (str "OCR TEXT:\n" azure-text)
                                                  feedback (str "\n\nYOUR PREVIOUS ANSWER HAD PROBLEMS:\n- "
                                                                (str/join "\n- " feedback)
                                                                "\nReturn the corrected Markdown for the whole page."))}
                                         {:type "input_image" :image_url image-url :detail "high"}]}]}
              (if effort
                ;; reasoning models: no temperature; the output budget also has to hold the reasoning
                {:model model :reasoning-effort effort :max-output-tokens 16000 :store false}
                {:model model :temperature 0 :max-output-tokens 4000 :store false}))]
    {:text  (strip-fences (or (openai/response-output-text res) ""))
     :usage {:in (get-in res [:usage :input_tokens] 0) :out (get-in res [:usage :output_tokens] 0)}
     :ms    (quot (- (System/nanoTime) t0) 1000000)}))

;; ── ⑤ Check, retry, fall back ────────────────────────────────────────────────

(defn convert-page
  "Up to 2 attempts; the second one is told what the check found. Falls back to the Azure text.
   `repair` is (fn [azure-text image-url feedback] → {:text :usage :ms})."
  [repair {:keys [azure-text image-url] :as page}]
  (loop [attempt 1 feedback nil usage {:in 0 :out 0} ms 0 history []]
    (let [{:keys [text error] :as r}
          (try (repair azure-text image-url feedback)
               (catch Exception e
                 ;; make-request wraps the HTTP error — the status and body are one cause down
                 (let [d (merge (ex-data e) (some-> e ex-cause ex-data))]
                   {:error (cond-> {:message (.getMessage e)}
                             (:status d) (assoc :status (:status d))
                             (:body d)   (assoc :body (subs (:body d) 0 (min 300 (count (:body d))))))
                    :usage {:in 0 :out 0} :ms 0})))
          usage   (merge-with + usage (:usage r))
          ms      (+ ms (:ms r))
          result  (when text (check/check-page azure-text text))
          history (conj history (if error {:error error} {:reasons (:reasons result) :text text}))]
      (cond
        (:ok? result)
        (assoc page :status (if (= 1 attempt) :ok :retried-ok) :attempts attempt :history history
                    :check result :model-text text :final-text text :usage usage :ms ms)

        (< attempt 2)
        (recur (inc attempt) (or (:reasons result) ["The request failed, try again."]) usage ms history)

        :else
        (assoc page :status (if error :error :fallback) :attempts attempt :history history
                    :check result :model-text text :final-text azure-text :usage usage :ms ms)))))

;; ── The whole book ───────────────────────────────────────────────────────────

(defn- daemon-pool [n]
  (Executors/newFixedThreadPool n (reify ThreadFactory
                                    (newThread [_ r]
                                      (doto (Thread. ^Runnable r "book-page") (.setDaemon true))))))

(defn improve-pages
  "Repairs every page of `pages` (split-pages output) and returns them in order, each with
   :final-text and :status (:ok :retried-ok :fallback :error, or :blank for a page without text).
   opts: :model :effort :parallel (pages at once for this book), :on-page (called after each page)."
  [client ^File pdf pages {:keys [model effort parallel on-page] :or {parallel 8 on-page (fn [])}}]
  (let [opts   {:model model :effort effort :prompt (load-prompt)}
        repair (fn [azure-text image-url feedback]
                 (.acquire max-model-calls)
                 (try (repair-page client opts azure-text image-url feedback)
                      (finally (.release max-model-calls))))
        pool   (daemon-pool parallel)]
    (with-open [doc (Loader/loadPDF pdf)]
      (try
        (->> pages
             (mapv (fn [{:keys [pdf-page azure-text] :as page}]
                     (.submit pool ^Callable
                              (fn []
                                (let [r (if (str/blank? azure-text)
                                          (assoc page :status :blank :final-text "" :usage {:in 0 :out 0})
                                          (let [img (locking doc (page-image-url doc (dec pdf-page)))]
                                            (dissoc (convert-page repair (assoc page :image-url img)) :image-url)))]
                                  (on-page)
                                  r)))))
             (mapv deref))
        (finally
          (.shutdown pool)
          (.awaitTermination pool 1 TimeUnit/MINUTES))))))

(defn summary
  "Counts and tokens of an improve-pages result, for the log."
  [results]
  {:status (frequencies (map :status results))
   :tokens (reduce #(merge-with + %1 (:usage %2)) {:in 0 :out 0} results)})
