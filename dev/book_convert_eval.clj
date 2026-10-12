(ns book-convert-eval
  "Eval for book conversion (doc/book-ocr-investigation.md): PDF → Azure Layout → per-page vision
   repair → check → book.md. Measures quality, time and cost before any production code exists.

   Output goes to dev/eval/out/book/<name>/ (git-ignored — book content is copyrighted):
     book.md                     the joined result, one marker per page
     pages/p003-azure.md         Azure's page text after clean-up (step ②) — the fallback
     pages/p003-model.md         the model's page (last attempt)
     report.edn                  per-page status, check results, tokens, ms; totals and cost
     azure-<hash>.json           cached Azure result, so re-runs don't pay for OCR again

   From the REPL:   (require 'book-convert-eval) (book-convert-eval/convert-book {...})
   From the shell (user.clj needs the :repl deps):
     clojure -Sdeps '{:deps {integrant/repl {:mvn/version \"0.3.3\"} stylefruits/gniazdo {:mvn/version \"1.2.0\"}}}' \\
       -M:dev -e \"(require 'book-convert-eval) (book-convert-eval/run-sample) (shutdown-agents)\""
  (:require [teachers-center-backend.core :refer [load-config]]
            [teachers-center-backend.openapi.core :as openai]
            [teachers-center-backend.book.check :as check]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.graphics.image PDImageXObject]
           [org.apache.pdfbox.rendering PDFRenderer ImageType]
           [javax.imageio ImageIO]
           [java.io ByteArrayOutputStream File]
           [java.security MessageDigest]
           [java.util Base64]
           [java.util.concurrent Executors TimeUnit]))

;; Prices in USD — check them before trusting the totals.
(def ^:private usd-per-azure-page (/ 10.0 1000))
(def ^:private usd-per-1m-tokens {"gpt-4o"  {:in 2.50 :out 10.00}
                                  "gpt-6-luna"  {:in 0.10 :out 0.50}
                                  "gpt-6-sol"   {:in 2.00 :out 10.00}
                                  "gpt-6-astra" {:in 10.00 :out 50.00}
                                  "gpt-4.1" {:in 2.00 :out 8.00}})

(def ^:private prompt-file "dev/eval/book-page-prompt.txt")
(def ^:private out-root "dev/eval/out/book")

;; ── ① Azure Layout ───────────────────────────────────────────────────────────

(defn- sha-12 [^File f]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (java.nio.file.Files/readAllBytes (.toPath f)))]
    (subs (apply str (map #(format "%02x" %) d)) 0 12)))

(defn- poll-operation [{:keys [key]} op-url]
  (let [deadline (+ (System/currentTimeMillis) (* 30 60 1000))]
    (loop []
      (let [res (http/get op-url {:headers          {"Ocp-Apim-Subscription-Key" key}
                                  :throw-exceptions false})
            body (json/parse-string (:body res) true)]
        (case (:status body)
          "succeeded" body
          "failed"    (throw (ex-info "Azure analyze failed" {:error (:error body)}))
          (if (> (System/currentTimeMillis) deadline)
            (throw (ex-info "Azure analyze timed out" {:status (:status body)}))
            (do (Thread/sleep 3000) (recur))))))))

(defn analyze-layout
  "Runs prebuilt-layout (Markdown output) on a PDF and returns :analyzeResult. Cached by file hash."
  [{:keys [endpoint key] :as azure} ^File pdf ^File out-dir]
  (let [cache (io/file out-dir (str "azure-" (sha-12 pdf) ".json"))]
    (if (.exists cache)
      (do (println "Azure: using cached" (.getName cache))
          (:analyzeResult (json/parse-string (slurp cache) true)))
      (do
        (when-not (and endpoint key)
          (throw (ex-info "AZURE_DI_ENDPOINT / AZURE_DI_KEY missing in .env" {})))
        (println "Azure: analyzing" (.getName pdf) "…")
        (let [url (str (str/replace endpoint #"/+$" "")
                       "/documentintelligence/documentModels/prebuilt-layout:analyze"
                       "?api-version=2024-11-30&outputContentFormat=markdown")
              res (http/post url {:headers          {"Ocp-Apim-Subscription-Key" key
                                                     "Content-Type"              "application/pdf"}
                                  :body             pdf
                                  :throw-exceptions false
                                  :socket-timeout   600000
                                  :connection-timeout 60000})]
          (when-not (= 202 (:status res))
            (throw (ex-info "Azure analyze request rejected" {:status (:status res) :body (:body res)})))
          (let [body (poll-operation azure (get-in res [:headers "operation-location"]))]
            (spit cache (json/generate-string body))
            (:analyzeResult body)))))))

(defn load-azure-json
  "Analyze results saved from the Document Intelligence Studio (one file per piece of the PDF)."
  [files]
  (mapv #(let [j (json/parse-string (slurp %) true)] (or (:analyzeResult j) j)) files))

;; ── ② Split into pages and clean up ──────────────────────────────────────────

(defn- page-range [page]
  (let [spans (:spans page)]
    [(apply min (map :offset spans))
     (apply max (map #(+ (:offset %) (:length %)) spans))]))

(defn- mark-examples
  "Wraps the handwritten spans inside [start end) as (Beispiel: …). In a workbook scan these are the
   book's printed example answers, so they're kept and labelled, never removed."
  [content [start end] styles]
  (let [spans (->> styles
                   (filter #(and (:isHandwritten %) (>= (or (:confidence %) 0) 0.8)))
                   (mapcat :spans)
                   (filter #(and (>= (:offset %) start) (<= (+ (:offset %) (:length %)) end)))
                   (sort-by :offset >))]
    (reduce (fn [text {:keys [offset length]}]
              (let [a (- offset start) b (+ a length)]
                (str (subs text 0 a) "(Beispiel: " (subs text a b) ")" (subs text b))))
            (subs content start end)
            spans)))

(defn- clean [text]
  (-> text
      (str/replace #"</?figure>" "")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn- printed-page
  "\"LEKTION 8 AB 92 zweiundneunzig\" → 92, \"dreiundneunzig 93 AB LEKTION 8\" → 93."
  [footers]
  (some->> footers
           (map #(str/replace % #"LEKTION\s+\d+" ""))
           (mapcat #(re-seq #"\b\d{1,3}\b" %))
           last
           parse-long))

(defn split-pages
  "One map per page: :azure-text (cleaned, step ②), :printed-page, :lesson."
  [{:keys [content pages styles]}]
  (for [page pages
        :let [rng     (page-range page)
              text    (mark-examples content rng styles)
              ;; Headers carry the unit number ("8 D"), not the page number — footers only.
              footers (map second (re-seq #"<!-- PageFooter=\"([^\"]*)\" -->" text))]]
    {:azure-text   (clean (check/tag-audio text))
     :printed-page (printed-page footers)
     :lesson       (some #(some-> (re-find #"LEKTION\s+(\d+)" %) second parse-long) footers)}))

;; ── ③ Page image ─────────────────────────────────────────────────────────────

(defn- page-images [^PDDocument doc idx]
  (let [res (.getResources (.getPage doc idx))]
    (for [n (.getXObjectNames res)
          :let [x (.getXObject res n)]
          :when (instance? PDImageXObject x)]
      x)))

(defn page-image-url
  "A scanned page is one big embedded image — use it as it is; otherwise render at 150 dpi."
  [^PDDocument doc idx]
  (let [[img & more] (page-images doc idx)
        bi  (if (and img (empty? more) (>= (.getWidth ^PDImageXObject img) 1000))
              (.getImage ^PDImageXObject img)
              (.renderImageWithDPI (PDFRenderer. doc) (int idx) 150 ImageType/RGB))
        out (ByteArrayOutputStream.)]
    (ImageIO/write bi "jpg" out)
    (str "data:image/jpeg;base64," (.encodeToString (Base64/getEncoder) (.toByteArray out)))))

;; ── ④ Vision model repair ────────────────────────────────────────────────────

(defn- load-prompt []
  ;; The manual test pasted the prompt and the OCR text into one message; here the rules are the
  ;; instructions and the OCR text goes with the image.
  (str/trim (str/replace (slurp prompt-file) #"OCR TEXT:\s*$" "")))

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
  "Up to 2 attempts; the second one is told what the check found. Falls back to the Azure text."
  [client opts {:keys [azure-text image-url] :as page}]
  (loop [attempt 1 feedback nil usage {:in 0 :out 0} ms 0 history []]
    (let [{:keys [text error] :as r}
          (try (repair-page client opts azure-text image-url feedback)
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

;; ── ⑥ Join and report ────────────────────────────────────────────────────────

(defn- page-marker [title {:keys [pdf-page printed-page lesson]}]
  (str "<!-- " (str/join " · " (remove nil? [title
                                              (when lesson (str "Lektion " lesson))
                                              (when printed-page (str "S. " printed-page))
                                              (str "PDF p. " pdf-page)]))
       " -->"))

(defn- cost [model {:keys [in out]}]
  (when-let [{p-in :in p-out :out} (usd-per-1m-tokens model)]
    (+ (* in p-in 1e-6) (* out p-out 1e-6))))

(defn summarize [{:keys [pages totals]}]
  (pp/print-table [:pdf-page :printed-page :status :attempts :added :lost :lost-nums :boxes :blanks :images :tok-in :tok-out :ms]
                  (for [{:keys [check usage] :as p} pages]
                    (merge (select-keys p [:pdf-page :printed-page :status :attempts :ms])
                           {:added     (count (:added-words check))
                            :lost      (reduce + 0 (vals (:lost-words check)))
                            :lost-nums (reduce + 0 (vals (:lost-numbers check)))
                            :boxes     (let [{:keys [azure model]} (:boxes check)]
                                         (str (:ticked azure) "/" (:empty azure) " -> " (:ticked model) "/" (:empty model)))
                            :blanks    (str (get-in check [:blanks :azure]) " -> " (get-in check [:blanks :model]))
                            :images    (:images check)
                            :tok-in    (:in usage)
                            :tok-out   (:out usage)})))
  (println)
  (pp/pprint totals))

(defn convert-book
  "opts:
   :name        output folder name (required)
   :title       book title for the page markers
   :pdf         path to the PDF (required — page images come from it)
   :azure-json  saved Studio results to use instead of calling Azure, in page order; together they
                must cover the PDF's pages from page 1 on (pieces of 2 pages from the F0 tier are fine)
   :pages       optional set of PDF page numbers (1-based) to convert, e.g. #{3 5 7}
   :model       default \"gpt-4o\"
   :parallel    model calls at once, default 8
   :answers-dir also write every model answer there as page-<book page>-<model>-try<n>.md
   :first-book-page  printed page number of PDF page 1, for those file names (default 1)"
  [{:keys [name title pdf azure-json pages model effort parallel answers-dir first-book-page]
    :or   {model "gpt-4o" parallel 8 first-book-page 1}}]
  (let [cfg      (load-config)
        {:keys [api-key base-url]} (:teachers-center-backend/openai-client cfg)
        client   (openai/create-client api-key base-url)
        out-dir  (doto (io/file out-root name) .mkdirs)
        pdf-file (io/file pdf)
        t0       (System/nanoTime)
        results  (if (seq azure-json)
                   (load-azure-json azure-json)
                   [(analyze-layout (:teachers-center-backend/azure-di cfg) pdf-file out-dir)])
        azure-ms (quot (- (System/nanoTime) t0) 1000000)
        all      (map-indexed #(assoc %2 :pdf-page (inc %1)) (mapcat split-pages results))
        todo     (vec (cond->> all (seq pages) (filter #(contains? pages (:pdf-page %)))))
        todo     (with-open [doc (Loader/loadPDF pdf-file)]
                   ;; PDFBox documents aren't thread-safe — take all images first, then go parallel
                   (mapv #(assoc % :image-url (page-image-url doc (dec (:pdf-page %)))) todo))
        opts     {:model model :effort effort :prompt (load-prompt)}
        label    (cond-> model effort (str "-" effort))
        pool     (Executors/newFixedThreadPool parallel)
        t1       (System/nanoTime)
        done     (try
                   (->> todo
                        (mapv (fn [p] (.submit pool ^Callable (fn []
                                                                (let [r (convert-page client opts p)]
                                                                  (println "page" (:pdf-page r) (:status r))
                                                                  r)))))
                        (mapv deref))
                   (finally (.shutdown pool) (.awaitTermination pool 1 TimeUnit/MINUTES)))
        model-ms (quot (- (System/nanoTime) t1) 1000000)
        usage    (reduce #(merge-with + %1 (:usage %2)) {:in 0 :out 0} done)
        totals   {:pages         (count done)
                  :status        (frequencies (map :status done))
                  :azure-ms      (when-not (seq azure-json) azure-ms)
                  :model-ms      model-ms
                  :tokens        usage
                  :usd-model     (cost model usage)
                  :usd-azure     (* usd-per-azure-page (count done))
                  :usd-per-page  (some-> (cost model usage) (+ (* usd-per-azure-page (count done))) (/ (max 1 (count done))))}
        pages-dir (doto (io/file out-dir "pages") .mkdirs)]
    (doseq [{:keys [pdf-page azure-text model-text]} done]
      (spit (io/file pages-dir (format "p%03d-azure.md" pdf-page)) azure-text)
      (when model-text (spit (io/file pages-dir (format "p%03d-model.md" pdf-page)) model-text)))
    ;; Every attempt, next to the page image and Azure text it was made from — for reading side by side
    (when answers-dir
      (doseq [{:keys [pdf-page history]} done
              [i {:keys [text]}] (map-indexed vector history)
              :when text]
        (spit (io/file answers-dir (format "page-%d-%s-try%d.md" (+ (dec first-book-page) pdf-page) label (inc i)))
              text)))
    (spit (io/file out-dir "book.md")
          (str/join "\n\n" (map #(str (page-marker title %) "\n\n" (:final-text %)) done)))
    (let [report {:pages  (mapv (fn [p] (-> (dissoc p :azure-text :model-text :final-text :image-url)
                                            (update :history #(mapv (fn [h] (dissoc h :text)) %))))
                                done)
                  :totals totals}]
      (spit (io/file out-dir "report.edn") (with-out-str (pp/pprint report)))
      (summarize report)
      (println "\nWritten to" (.getPath out-dir))
      report)))

(def ^:private downloads "C:/Users/ivang/Downloads/")

(defn run-raw
  "One model answer per page, written straight to `answers-dir` as page-<book page>-<model>[-<effort>].md
   — no check, no retry. Compare the files afterwards (check-files, or by reading them).
   opts: :pdf :azure-json :answers-dir :first-book-page :model :effort :pages :parallel (as convert-book)."
  [{:keys [pdf azure-json answers-dir first-book-page model effort pages parallel]
    :or   {first-book-page 1 parallel 8}}]
  (let [{:keys [api-key base-url]} (:teachers-center-backend/openai-client (load-config))
        client (openai/create-client api-key base-url)
        label  (cond-> model effort (str "-" effort))
        todo   (->> (mapcat split-pages (load-azure-json azure-json))
                    (map-indexed #(assoc %2 :pdf-page (inc %1)))
                    (filter #(or (empty? pages) (contains? pages (:pdf-page %))))
                    vec)
        todo   (with-open [doc (Loader/loadPDF (io/file pdf))]
                 (mapv #(assoc % :image-url (page-image-url doc (dec (:pdf-page %)))) todo))
        opts   {:model model :effort effort :prompt (load-prompt)}
        pool   (Executors/newFixedThreadPool parallel)
        t0     (System/nanoTime)
        rows   (try
                 (->> todo
                      (mapv (fn [{:keys [pdf-page azure-text image-url]}]
                              (.submit pool ^Callable
                                       (fn []
                                         (let [book-page (+ (dec first-book-page) pdf-page)
                                               f         (io/file answers-dir (format "page-%d-%s.md" book-page label))
                                               r         (try (repair-page client opts azure-text image-url nil)
                                                              (catch Exception e {:error (.getMessage e)}))]
                                           (when (:text r) (spit f (:text r)))
                                           (println "page" book-page (if (:text r) (.getName f) (str "ERROR " (:error r))))
                                           {:page book-page :file (when (:text r) (.getName f)) :error (:error r)
                                            :tok-in (get-in r [:usage :in]) :tok-out (get-in r [:usage :out]) :ms (:ms r)})))))
                      (mapv deref))
                 (finally (.shutdown pool) (.awaitTermination pool 1 TimeUnit/MINUTES)))
        usage  {:in (reduce + 0 (keep :tok-in rows)) :out (reduce + 0 (keep :tok-out rows))}]
    (pp/print-table [:page :file :error :tok-in :tok-out :ms] rows)
    {:label label :pages (count rows) :errors (count (filter :error rows))
     :ms (quot (- (System/nanoTime) t0) 1000000) :tokens usage :usd-model (cost model usage)}))

(defn run-raw-sample
  "run-raw on the 10-page sample (book pages 90–99), answers into Downloads/ocr-test."
  [model effort]
  (run-raw {:pdf             (str downloads "schritte-2-p101-110.pdf")
            :azure-json      (mapv #(str downloads %) ["schritte-2-p101-110-mic.json"
                                                       "92_93.txt" "94_95.txt" "96_97.txt" "98_99.txt"])
            :answers-dir     (str downloads "ocr-test")
            :first-book-page 90
            :model           model
            :effort          effort}))

(defn check-files
  "Checks answers saved by hand (Playground / ChatGPT) against the Azure page text they were made
   from: every page-<n>-chatgpt.md.txt in `dir` against page-<n>-azure.md."
  ([] (check-files (str downloads "ocr-test")))
  ([dir]
   (pp/print-table
     [:page :answer :ok? :added :lost :lost-nums :added-nums :dangling :boxes :blanks :images]
     (for [f     (sort (.listFiles (io/file dir)))
           :let  [[_ page answer] (re-matches #"page-(\d+)-(.+?)\.md(?:\.txt)?" (.getName ^File f))]
           :when (and page (not= answer "azure"))
           :let  [azure (io/file dir (str "page-" page "-azure.md"))
                  r     (check/check-page (slurp azure) (slurp f))]]
       {:page      page
        :answer    answer
        :added-nums (str/join " " (keys (:added-numbers r)))
        :dangling  (:dangling-ticks r)
        :ok?       (:ok? r)
        :added     (str/join " " (keys (:added-words r)))
        :lost      (str/join " " (keys (:lost-words r)))
        :lost-deco (str/join " " (keys (:lost-decoration r)))
        :lost-nums (str/join " " (keys (:lost-numbers r)))
        :boxes     (let [{:keys [azure model]} (:boxes r)]
                     (str (:ticked azure) "/" (:empty azure) " -> " (:ticked model) "/" (:empty model)))
        :blanks    (str (get-in r [:blanks :azure]) " -> " (get-in r [:blanks :model]))
        :images    (:images r)}))))

(defn run-sample
  "Run 1: the 10-page sample (book pages 90–99) with the Azure JSON saved from the Studio."
  ([] (run-sample "gpt-4o" nil))
  ([model effort]
  (-> (convert-book {:name       (cond-> (str "schritte-2-sample-" model) effort (str "-" effort))
                 :model      model
                 :effort     effort
                 :answers-dir (str downloads "ocr-test")
                 :first-book-page 90
                 :title      "Schritte International neu 2"
                 :pdf        (str downloads "schritte-2-p101-110.pdf")
                 :azure-json (mapv #(str downloads %) ["schritte-2-p101-110-mic.json"
                                                       "92_93.txt" "94_95.txt" "96_97.txt" "98_99.txt"])})
      :totals)))

(comment
  (run-sample)
  (run-sample "gpt-4.1" nil)
  (run-sample "gpt-6-luna" "medium")

  ;; Only the three pages tested by hand (book pages 92, 94, 96 = PDF pages 3, 5, 7)
  (convert-book {:name "schritte-2-sample-3" :title "Schritte International neu 2"
                 :pdf (str downloads "schritte-2-p101-110.pdf")
                 :azure-json (mapv #(str downloads %) ["schritte-2-p101-110-mic.json"
                                                       "92_93.txt" "94_95.txt" "96_97.txt" "98_99.txt"])
                 :pages #{3 5 7}})

  ;; Run 2: the full book through Azure (needs AZURE_DI_ENDPOINT / AZURE_DI_KEY on an S0 resource)
  (convert-book {:name "schritte-2-full" :title "Schritte International neu 2"
                 :pdf (str downloads "Schritte International neu 2.pdf")})
  )

(defn recheck-book
  "Runs the check again, with no model calls, on the answers a convert-book run saved: each page's
   last answer (pages/pNNN-model.md) against Azure's text rebuilt from the cached Azure JSON, so a
   change to step ② counts too. `dirs` are output folders of the same book; a later one wins for
   the pages it has (a re-run after errors). Prints the pages that changed and the totals."
  [azure-json dirs]
  (let [azure  (into {} (map-indexed (fn [i p] [(inc i) (:azure-text p)]))
                     (mapcat split-pages (load-azure-json [azure-json])))
        status (into {} (for [d dirs
                              p (:pages (read-string (slurp (io/file out-root d "report.edn"))))
                              :when (not= :error (:status p))]
                          [(:pdf-page p) [d (:status p)]]))
        rows   (for [[page [d old]] (sort status)
                     :let [f (io/file out-root d "pages" (format "p%03d-model.md" page))
                           r (check/check-page (azure page) (slurp f))]]
                 {:pdf-page  page
                  :old       old
                  :new       (cond (not (:ok? r))     :fallback
                                   (= :fallback old) :retried-ok
                                   :else             old)
                  :added     (str/join " " (keys (:added-words r)))
                  :lost      (str/join " " (keys (:lost-words r)))
                  :lost-nums (str/join " " (keys (:lost-numbers r)))
                  :added-nums (str/join " " (keys (:added-numbers r)))
                  :dangling  (:dangling-ticks r)})]
    (pp/print-table [:pdf-page :old :new :added :lost :lost-nums :added-nums :dangling]
                    (filter #(or (not= (:old %) (:new %)) (= :fallback (:new %))) rows))
    (let [totals {:pages (count rows)
                  :old   (frequencies (map :old rows))
                  :new   (frequencies (map :new rows))}]
      (println) (pp/pprint totals)
      totals)))
