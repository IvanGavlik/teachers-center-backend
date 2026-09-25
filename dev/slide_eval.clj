(ns slide-eval
  "POC 2 eval: runs dev/eval/requests.edn through the real generate/edit path and reports how the
   AI uses the layout schema. Run sequentially (the OpenAI response is captured via with-redefs).

   From the REPL:   (require 'slide-eval) (slide-eval/run-eval)
   From the shell (user.clj needs the :repl deps):
     clojure -Sdeps '{:deps {integrant/repl {:mvn/version \"0.3.3\"} stylefruits/gniazdo {:mvn/version \"1.2.0\"}}}' \\
       -M:dev -e \"(require 'slide-eval) (slide-eval/run-eval) (shutdown-agents)\""
  (:require [teachers-center-backend.core :refer [load-config]]
            [teachers-center-backend.openapi.core :as openai]
            [teachers-center-backend.conversation.core :as conversation]
            [teachers-center-backend.conversation.slide-schema :as slide-schema]
            [teachers-center-backend.conversation.ws :as ws]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(defn- make-client []
  (let [{:keys [api-key base-url]} (:teachers-center-backend/openai-client (load-config))]
    (openai/create-client api-key base-url)))

(defn- run-case [client f]
  (let [captured (atom nil)
        real     openai/responses-api
        t0       (System/nanoTime)
        outcome  (try
                   {:result (with-redefs [openai/responses-api (fn [& args]
                                                                 (let [res (apply real args)]
                                                                   (reset! captured res)
                                                                   res))]
                              (f client))}
                   (catch Exception e
                     {:error (merge {:message (.getMessage e)}
                                    (select-keys (ex-data e) [:error-code :reason :refusal]))}))
        res      @captured]
    (assoc outcome
           :raw   (try (some-> res openai/response-output-text (json/parse-string true))
                       (catch Exception _ nil))
           :usage (select-keys (:usage res) [:input_tokens :output_tokens])
           :ms    (quot (- (System/nanoTime) t0) 1000000))))

(defn- generate [{:keys [language level age-group native-language content]}]
  (fn [client]
    (conversation/generate-conversation
      client
      {:type            :conversation
       :conversation-id nil
       :content         content
       :requirements    (ws/normalize-requirements {:language language :level level
                                                    :age-group age-group :native-language native-language}
                                                   nil)
       :book-ids        []}
      nil)))

(defn- edit [gen-case deck {:keys [slide content]}]
  (let [idx (or (first (keep-indexed #(when (= slide (:layout %2)) %1) (:slides deck))) 0)]
    (fn [client]
      (conversation/edit-slide
        client
        {:type         :edit
         :content      content
         :requirements (ws/normalize-requirements (select-keys gen-case [:language :level :age-group :native-language]) nil)
         :edit         {:slideIndex idx :currentSlide (get-in deck [:slides idx]) :originalRequest (:content gen-case)}
         :book-ids     []}
        nil))))

;; ── Summary ─────────────────────────────────────────────────────────────────

(defn- pct [n total] (if (pos? total) (format "%.0f%%" (* 100.0 (/ n total))) "-"))

(defn summarize [{:keys [generate edit]}]
  (let [decks       (filter #(get-in % [:result :slides]) generate)
        slides      (mapcat #(get-in % [:result :slides]) decks)
        raw-slides  (mapcat #(get-in % [:raw :slides]) decks)
        layouts     (frequencies (map :layout slides))
        clarified   (filter #(get-in % [:result :requirements-not-met]) generate)
        wrong-kind  (remove #(= (:expect %) (if (get-in % [:result :requirements-not-met]) :clarify
                                               (when (get-in % [:result :slides]) :slides)))
                            generate)
        violations  (slide-schema/violations raw-slides)
        count-miss  (keep #(let [asked (some->> (:content %) (re-find #"(\d+)\s*(slides|slide|Folien|diapositivas|slajdova)") second parse-long)
                                 got   (count (get-in % [:result :slides]))]
                             (when (and asked (get-in % [:result :slides]) (not= asked got))
                               {:id (:id %) :asked asked :got got}))
                          generate)
        all-cases   (concat generate edit)]
    {:cases              (count generate)
     :errors             (vec (keep #(when (:error %) {:id (:id %) :error (:error %)}) all-cases))
     :decks              (count decks)
     :clarifications     (count clarified)
     :wrong-outcome      (mapv #(select-keys % [:id :expect]) wrong-kind)
     :slide-count-miss   (vec count-miss)
     :slides             (count slides)
     :layouts            (into (sorted-map) layouts)
     :bullets-share      (pct (get layouts "bullets" 0) (count slides))
     :violations-by-rule (into (sorted-map) (frequencies (map :rule violations)))
     ;; [left right] polarity pairs — [positive positive] draws two green ✓ columns for two correct forms
     :comparison-polarity (frequencies (for [s slides :when (= "comparison" (:layout s))]
                                         [(get-in s [:left :polarity]) (get-in s [:right :polarity])]))
     :violations         violations
     :translations       (into {} (for [d decks]
                                    [(:id d) {:native (:native-language d)
                                              :translated (count (keep :translation (mapcat :items (filter #(= "examples" (:layout %)) (get-in d [:result :slides])))))}]))
     :edits              (mapv (fn [e] {:id (:id e) :layout (get-in e [:result :edit :slide :layout]) :error (:error e)}) edit)
     :avg-ms             (when (seq all-cases) (quot (reduce + (map :ms all-cases)) (count all-cases)))
     :avg-output-tokens  (when (seq all-cases) (quot (reduce + (keep #(get-in % [:usage :output_tokens]) all-cases)) (count all-cases)))
     :max-output-tokens  (reduce max 0 (keep #(get-in % [:usage :output_tokens]) all-cases))}))

;; ── Run ─────────────────────────────────────────────────────────────────────

(defn run-eval
  "Runs every case (or only the ids given) and writes dev/eval/out/<ts>.edn + <ts>-decks.json."
  ([] (run-eval nil))
  ([only-ids]
   (let [client   (make-client)
         spec     (-> "dev/eval/requests.edn" slurp edn/read-string)
         wanted?  (if (seq only-ids) (set only-ids) (constantly true))
         gen      (vec (for [c (:generate spec) :when (wanted? (:id c))]
                         (do (println "generate" (:id c))
                             (merge c (run-case client (generate c))))))
         by-id    (into {} (map (juxt :id identity) gen))
         edits    (vec (for [e (:edit spec)
                             :let [g (by-id (:deck e))]
                             :when (get-in g [:result :slides])]
                         (do (println "edit" (:id e))
                             (merge e (run-case client (edit g (:result g) e))))))
         results  {:generate gen :edit edits}
         summary  (summarize results)
         ts       (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
         out      (io/file "dev/eval/out")]
     (.mkdirs out)
     (spit (io/file out (str ts ".edn")) (with-out-str (pp/pprint (assoc results :summary summary))))
     ;; fixture-shaped decks, for feeding real AI output to the add-in POC renderer
     (spit (io/file out (str ts "-decks.json"))
           (json/generate-string (into (sorted-map) (for [g gen :when (get-in g [:result :slides])]
                                                      [(name (:id g)) (:result g)]))
                                 {:pretty true}))
     (pp/pprint (dissoc summary :violations))
     (println "written" (str "dev/eval/out/" ts ".edn"))
     summary)))
