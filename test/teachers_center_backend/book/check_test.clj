(ns teachers-center-backend.book.check-test
  (:require [clojure.test :refer [deftest is testing]]
            [teachers-center-backend.book.check :as check]))

;; Cases from the manual test of doc/book-ocr-investigation.md — the ways a model invents content.

(def ^:private azure-gaps
  "b Emilia\nWirtschaft. Am Abend hat sie einen\nals\nin einem Restaurant.\nsdieturt boJ")

(deftest layout-only-changes-pass
  (let [r (check/check-page azure-gaps
                            "b Emilia ____ Wirtschaft. Am Abend hat sie einen ____ als ____ in einem Restaurant.\nsdieturt boJ")]
    (is (:ok? r))
    (is (= {:azure 0 :model 3} (:blanks r)))))

(deftest a-solved-gap-fails
  (testing "a word that isn't on the page (the page only has the scrambled form)"
    (let [r (check/check-page azure-gaps
                              "b Emilia [studiert] Wirtschaft. Am Abend hat sie einen ____ als ____ in einem Restaurant.\nsdieturt boJ")]
      (is (not (:ok? r)))
      (is (= {"studiert" 1} (:added-words r))))))

(deftest a-word-bank-phrase-copied-into-a-gap-fails
  (let [azure "Ich habe Ihre Anzeige gelesen wie ist die Vergütung\nGuten Tag. Mein Name ist Victoria Peterson.\n(a)"
        model "Ich habe Ihre Anzeige gelesen | wie ist die Vergütung\n○ Guten Tag. Mein Name ist Victoria Peterson. Ich habe Ihre Anzeige gelesen (a)"
        r     (check/check-page azure model)]
    (is (not (:ok? r)))
    (is (= #{"ich" "habe" "ihre" "anzeige" "gelesen"} (set (keys (:added-words r)))))))

(deftest tagged-notes-may-add-words
  (let [r (check/check-page "[Audio: CD 2, Track 5] a Hören Sie\n(Beispiel: bin gekommen)"
                            "[Audio: CD 2, Track 5]\na Hören Sie\n[Image: two people in a café with a laptop]\n(Beispiel: bin gekommen)")]
    (is (:ok? r))
    (is (= 1 (:images r)))))

(deftest dropped-item-numbers-fail
  (testing "the word count can't see a dropped `1 ☐ … 6 ☐` list — the number count can"
    (let [azure (apply str (for [i (range 1 7)] (str i "\n☐\nSatz " (char (+ 96 i)) "xx.\n")))
          model (apply str (for [i (range 1 7)] (str "Satz " (char (+ 96 i)) "xx.\n")))
          r     (check/check-page azure model)]
      (is (not (:ok? r)))
      (is (= 6 (reduce + (vals (:lost-numbers r))))))))

(deftest the-same-letters-split-differently-pass
  (testing "a gap's example letters split out: einer → ein er"
    (is (:ok? (check/check-page "| ein | einer Woche |" "| ein___ | ein er Woche |"))))
  (testing "a hyphenated word joined: ARBEITS- ZEIT → Arbeitszeit"
    (is (:ok? (check/check-page "Thema\nARBEITS -\nZEIT" "Thema Arbeitszeit"))))
  (testing "but a split that adds a new word still fails"
    (is (= {"war" 1} (:added-words (check/check-page "Ich bin hier" "Ich war bin hier"))))))

(deftest redrawn-letter-gaps-pass
  (is (:ok? (check/check-page "netten K\\_||_g _. n in jungem T \\_.\\_. m.\nHier arbeiten Sie im B e r e i c h"
                              "netten K__ll__g__n in jungem T__ __m.\nHier arbeiten Sie im B e r e i c h"))))

(deftest text-from-a-picture-may-move-into-its-image-line
  (let [r (check/check-page "Bis sechs Uhr.\nAUSBILDUNG"
                            "Bis sechs Uhr.\n[Image: three people at a table with a sign AUSBILDUNG]")]
    (is (:ok? r))
    (is (empty? (:lost-words r)))))

(deftest decoration-may-go
  (testing "score lines, labels and crossword letters Azure read in capitals"
    (let [r (check/check-page "a Text hier.\n1\n/9 Punkte\n2 /7 Punkte\nWÖRTER\nGRAMMATIK\nKOMMUNIKATION\nPLASTIBANK\nEWÄHLEN\nDE\nIn"
                              "a Text hier.")]
      (is (:ok? r))
      (is (empty? (:lost-numbers r)))
      (is (contains? (:lost-decoration r) "plastibank"))))
  (testing "but a word Azure also read in normal case is content"
    (is (= {"arbeitszeit" 1}
           (:lost-words (check/check-page "Thema Arbeit Arbeitszeit\nARBEITSZEIT" "Thema Arbeit\n[Image: a sign ARBEITSZEIT]")))))
  (testing "the printed footer may be written out as text"
    (is (:ok? (check/check-page "Text.\n<!-- PageFooter=\"neunundneunzig 99\" -->" "Text.\nneunundneunzig 99")))))

(deftest numbering-an-ordering-task-fails
  (testing "\"Ordnen Sie das Gespräch\": same words, but numbered into the solution order"
    (let [azure "a Ordnen Sie das Gespräch.\n☐\n· Ja. Schicken Sie mir Ihre Bewerbung.\n☐\n· Reisebüro Globalreisen, guten Tag.\n☐\n· Auf Wiederhören."
          model "a Ordnen Sie das Gespräch.\n1. [ ] Reisebüro Globalreisen, guten Tag.\n2. [ ] Ja. Schicken Sie mir Ihre Bewerbung.\n3. [ ] Auf Wiederhören."
          r     (check/check-page azure model)]
      (is (not (:ok? r)))
      (is (= {"1" 1 "2" 1 "3" 1} (:added-numbers r))))))

(deftest ticks-that-mark-no-option-fail
  (testing "Azure's false ticks (speaker symbols) carried over to the end of dialogue lines"
    (let [r (check/check-page "☒ · Hattest (a) du ein schönes Wochenende?\n☒ Wir (h) in der Firma."
                              "◆ Hattest (a) du ein schönes Wochenende? [x]\n◆ Wir ____ (h) in der Firma. [x]")]
      (is (not (:ok? r)))
      (is (= 2 (:dangling-ticks r)))))
  (testing "real ticks on an option, including the \"/\" (no preposition) choice, pass"
    (let [r (check/check-page "Ich arbeite ☒ seit ☐ vor drei Jahren. Zuerst habe ich ☒ / ☐ seit (b) ein Jahr"
                              "Ich arbeite [x] seit [ ] vor drei Jahren. Zuerst habe ich [x] / [ ] seit (b) ein Jahr")]
      (is (:ok? r))
      (is (zero? (:dangling-ticks r))))))

(deftest score-ranges-and-a-few-labels-may-go
  (let [r (check/check-page "1 /9 Punkte\n☒ 0-4 ☐ 5-7\nKOMMUNIKATION\na Text hier"
                            "1 ____ /9 Punkte\na Text hier")]
    (is (:ok? r))
    (is (empty? (:lost-words r)))
    (is (= {"kommunikation" 1} (:lost-decoration r)))))
