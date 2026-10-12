(ns teachers-center-backend.book.check-test
  (:require [clojure.test :refer [are deftest is testing]]
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

;; False positives found on the full 211-page run (doc/Results-211-page-boo-converted.md §3.1).

(deftest audio-markers-are-tagged
  (testing "the ways Azure reads CD, ◀)) icon and track"
    (are [azure tag] (= (str tag " a Hören Sie") (check/tag-audio (str azure " a Hören Sie")))
      "2 41) 10"      "[Audio: CD 2, Track 10]"
      "2 4) 17-19"    "[Audio: CD 2, Track 17-19]"
      "2 (1) 20"      "[Audio: CD 2, Track 20]"
      "2 M) 25-27"    "[Audio: CD 2, Track 25-27]"
      "2 )) 29"       "[Audio: CD 2, Track 29]"
      "### 44) 38"    "[Audio: CD 4, Track 38]"
      "## 50) 33-34"  "[Audio: CD 5, Track 33-34]"
      "#### 3 41) ) 32" "[Audio: CD 3, Track 32]"))
  (testing "in a table cell"
    (is (= "<td>[Audio: CD 2, Track 30-33] </td>" (check/tag-audio "<td>2 41) 30-33</td>"))))
  (testing "an item number with a bracket isn't one"
    (is (= "1) 12 Uhr" (check/tag-audio "1) 12 Uhr")))))

(deftest audio-markers-the-model-copied-pass
  (let [azure "2 41) 38\na Hören Sie und kreuzen Sie an.\n2 41) 39 b Hören Sie noch einmal."]
    (testing "untagged, bold, with an em space, or as [Audio: …]"
      (is (:ok? (check/check-page azure "**2 41) 38** ### a Hören Sie und kreuzen Sie an.\n[Audio: CD 2, Track 39] b Hören Sie noch einmal."))))
    (testing "the CD number left outside the tag, or the icon dropped"
      (is (:ok? (check/check-page azure "2 [Audio: 38]\na Hören Sie und kreuzen Sie an.\n2 39 b Hören Sie noch einmal."))))
    (testing "but an item number dropped next to it still counts"
      (is (not (:ok? (check/check-page (str azure "\n1 Ja.\n2 Nein.\n3 Gut.") (str azure "\nJa.\nNein.\nGut."))))))))

(deftest the-footer-may-be-written-out-with-its-numbers
  (is (:ok? (check/check-page "Folge 10: Augen.\n<!-- PageFooter=\"LEKTION 10 118 einhundertachtzehn\" -->"
                              "Folge 10: Augen.\nLEKTION 10 118 einhundertachtzehn"))))

(deftest photo-numbers-may-move-into-the-image-line
  (let [azure "1\nALTE\nFolge 14: Ende gut\n6\n5\n4\n1 Sehen Sie die Fotos an."]
    (is (:ok? (check/check-page azure (str "[Image: 1 — Torte mit „ALTE“.]\n[Image 4: Eine Familie feiert.]\n"
                                           "[Image: Freunde (Foto 5).]\n[Image: 6 — Freunde lächeln.]\n"
                                           "Folge 14: Ende gut\n1 Sehen Sie die Fotos an."))))
    (testing "[Image N: …] is an image line, its words don't count as added"
      (is (empty? (:added-words (check/check-page "Text." "Text.\n[Image 2: A doctor walking down a corridor.]")))))))

(deftest answer-circles-read-as-zero-may-go
  (is (:ok? (check/check-page "Was macht Amidou?\n0\nlaufen\n0\n0\nMusik hören\n0"
                              "Was macht Amidou?\n[ ] laufen\n[ ] Musik hören"))))

(deftest crossed-out-letters-stay-part-of-the-word
  (is (:ok? (check/check-page "## A euxre\nPeter ist müde." "## A eu~~x~~re\nPeter ist müde."))))

(deftest a-capital-i-read-as-l-may-be-fixed
  (is (:ok? (check/check-page "loanna ist müde." "Ioanna ist müde.")))
  (testing "but no other spelling fix"
    (is (= {"weniger" 1} (:added-words (check/check-page "Ich esse wenig." "Ich esse weniger.")))))
  (testing "nor letters from another alphabet (Cyrillic е, л)"
    (let [r (check/check-page "Er schnel Kaffee." "Er schnел Kaffee.")]
      (is (not (:ok? r)))
      (is (= {"schnел" 1} (:foreign-words r))))))

(deftest html-entities-are-not-words
  (is (:ok? (check/check-page "| B | Ü S |" "| B | Ü &nbsp;&nbsp;&nbsp; S |"))))

;; False positives from judging the prompt-v4 fallbacks against the page images (results doc §8).

(deftest spacing-in-number-lists-and-dates-does-not-count
  (is (:ok? (check/check-page "1,2,5\nEr ist am 24.2. 1990 geboren. Vom 12 .- 15.6."
                              "1, 2, 5\nEr ist am 24. 2. 1990 geboren. Vom 12.–15. 6.")))
  (testing "but item numbers still count"
    (is (= {"1" 1 "2" 1 "3" 1}
           (:added-numbers (check/check-page "Ja.\nNein.\nGut." "1. Ja.\n2. Nein.\n3. Gut."))))))

(deftest an-audio-marker-without-a-track-is-tagged
  (is (= "[Audio: CD 2]\na Hören Sie." (check/tag-audio "2 0)\na Hören Sie.")))
  (is (:ok? (check/check-page "2 0)\n2 Bitte hören Sie zu!\n3 Machen Sie das!\n4 Gut."
                              "2 Bitte hören Sie zu!\n3 Machen Sie das!\n4 Gut."))))

(deftest real-ticks-before-an-example-or-a-number-option
  (testing "the book's ticked example item"
    (is (zero? (:dangling-ticks (check/check-page "1 ☒ du bitte nicht so schnell"
                                                  "1 [x] ____ (Beispiel: Würdest) du bitte nicht so schnell")))))
  (testing "options that are numbers"
    (is (zero? (:dangling-ticks (check/check-page "Getreidegasse ☒ 9 ☐ 17" "Getreidegasse [x] 9 [ ] 17")))))
  (testing "but a tick before an empty blank, or a score range, still counts"
    (is (= 2 (:dangling-ticks (check/check-page "Er ☒ Kaffee.\n☒ 0-4 ☐ 5-7" "Er [x] ____ Kaffee.\n[x] 0-4 [ ] 5-7"))))))

(deftest a-footer-azure-did-not-mark-may-go
  (is (:ok? (check/check-page "1\nJa, das schmeckt.\n2\nNein.\n3\nGut.\nLEKTION 13 AB\n144 einhundertvierundvierzig"
                              "1 Ja, das schmeckt.\n2 Nein.\n3 Gut.")))
  (testing "a number exercise isn't a footer"
    (is (not (:ok? (check/check-page "12 zwölf\n13 dreizehn\n14 vierzehn\n15 fünfzehn\n16 sechzehn\n17 siebzehn" "Zahlen."))))))

(deftest a-letter-gap-split-by-spaces-is-one-gap-word
  (is (:ok? (check/check-page "Ich habe einen guten Deutschk _n_t_s_n." "Ich habe einen guten Deutschk_n_t_s__n.")))
  (testing "words next to a blank still count"
    (is (= {"schönen" 1} (:added-words (check/check-page "Er hat ein ____ Monat." "Er hat ein schönen ____ Monat."))))))

(deftest letter-gap-joining-leaves-other-words-alone
  (testing "a speaker symbol Azure read as O before a stray _"
    (is (:ok? (check/check-page "O _Und? Passt Ihnen die Hose?" "○ Und? Passt Ihnen die Hose?"))))
  (testing "Azure's escaped dots in a number list"
    (is (:ok? (check/check-page "4\\. 3\\. 2\\. Die letzten Ziffern" "| 4. 3. 2. | Die letzten Ziffern |")))))
