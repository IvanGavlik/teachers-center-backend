(ns teachers-center-backend.book.limits
  "Guardrails for book uploads (doc/book-production-plan.md, Phase 4). There are no accounts, so
   anyone can start OCR and the vision model at ~$1.40 per 100 pages. Two daily limits:

   - pages for the whole service per day (a cap on the worst day's cost)
   - books per IP per day (one user can't use up the day; a school often shares one IP, so not 3)

   A cached book costs nothing and is never counted. Counters are in memory and reset at midnight UTC
   (and on a restart/deploy — fine for a cost cap on an instance that rarely restarts)."
  (:import [java.time LocalDate ZoneOffset]))

(defonce ^:private usage (atom {:day nil :pages 0 :books-by-ip {}}))

(defn- today [] (str (LocalDate/now ZoneOffset/UTC)))

(defn- current
  "Usage for today, starting fresh on a new day."
  [u day]
  (if (= day (:day u)) u {:day day :pages 0 :books-by-ip {}}))

(defn try-reserve!
  "Counts a book of `pages` for `ip` if both daily limits allow it. Returns nil when reserved, or
   :daily-pages / :ip-books naming the limit that's full. Reserved pages aren't given back if the
   job fails later — the cost (OCR) has usually happened by then."
  [ip pages {:keys [daily-pages ip-daily-books]}]
  (let [day    (today)
        result (atom nil)]
    (swap! usage
           (fn [u]
             (let [u (current u day)]
               (cond
                 (> (+ (:pages u) pages) daily-pages)
                 (do (reset! result :daily-pages) u)

                 (>= (get-in u [:books-by-ip ip] 0) ip-daily-books)
                 (do (reset! result :ip-books) u)

                 :else
                 (do (reset! result nil)
                     (-> u (update :pages + pages) (update-in [:books-by-ip ip] (fnil inc 0))))))))
    @result))

(defn snapshot
  "Today's usage, for logs."
  []
  (let [u (current @usage (today))]
    {:day (:day u) :pages (:pages u) :ips (count (:books-by-ip u))}))

(defn reset-for-tests! []
  (reset! usage {:day nil :pages 0 :books-by-ip {}}))
