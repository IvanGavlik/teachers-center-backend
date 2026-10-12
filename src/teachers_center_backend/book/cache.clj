(ns teachers-center-backend.book.cache
  "Converted books, reused by file fingerprint (doc/book-production-plan.md, Phase 3): a teacher who
   uploads exactly the same PDF as someone before gets the finished text in seconds, with no OCR or
   vision model.

   The key is the SHA-256 of the PDF's bytes, not its name: names like \"Schritte 2.pdf\" or
   \"scan001.pdf\" are shared by different files, and a name match would hand one teacher's file
   (maybe a worksheet with student names) to someone else. With the hash, a hit is only possible
   for someone who already has the exact same file.

   No database: the converted book is an OpenAI file whose name ends in [sha256-<hash>].md. The map
   below is only an index of those files, rebuilt from OpenAI's file list after a restart."
  (:require [teachers-center-backend.openapi.file-search :as file-search]
            [clojure.tools.logging :as log])
  (:import [java.io File FileInputStream]
           [java.security DigestInputStream MessageDigest]))

(defn sha256
  "Hex SHA-256 of a file, streamed (a 60 MB PDF takes a fraction of a second)."
  [^File f]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (with-open [in (DigestInputStream. (FileInputStream. f) md)]
      (let [buf (byte-array 65536)]
        (while (pos? (.read in buf)))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(def ^:private marker #"\[sha256-([0-9a-f]{64})\]\.md$")

(defn cached-filename
  "\"Schritte 2 [sha256-…].md\" — the title stays readable (file_search shows the filename)."
  [title sha]
  (str (subs title 0 (min 80 (count title))) " [sha256-" sha "].md"))

(defonce ^:private index (atom nil)) ; nil until loaded: {sha {:file-id :expires-at}}

(defn- now-s [] (quot (System/currentTimeMillis) 1000))

(defn- load-index! [openai-client]
  (try
    (let [entries (for [{:keys [id filename expires_at]} (file-search/list-files openai-client "assistants")
                        :let [[_ sha] (some->> filename (re-find marker))]
                        :when sha]
                    [sha {:file-id id :expires-at expires_at}])]
      (log/info "Book cache loaded" {:books (count entries)})
      (into {} entries))
    (catch Exception e
      ;; no cache is fine — every book is just converted again
      (log/warn e "Could not load the book cache, starting empty")
      {})))

(defn lookup
  "The cached file id for this fingerprint, if it exists and won't expire within the hour."
  [openai-client sha]
  (when (nil? @index)
    (compare-and-set! index nil (load-index! openai-client)))
  (let [{:keys [file-id expires-at]} (get @index sha)]
    (when (and file-id (or (nil? expires-at) (> expires-at (+ (now-s) 3600))))
      file-id)))

(defn remember!
  "Adds a converted book's file to the index."
  [sha file-id expires-at]
  (swap! index (fnil assoc {}) sha {:file-id file-id :expires-at expires-at}))

(defn forget!
  "Drops an entry whose file turned out to be gone (deleted early or expired)."
  [sha]
  (swap! index #(some-> % (dissoc sha))))
