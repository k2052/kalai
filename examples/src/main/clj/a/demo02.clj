(ns a.demo02
  (:refer-clojure :exclude [format]))

(defn getDigitsMap ^{:t {:map [:char :int]}} []
  (let [^{:t {:map [:char :int]}} m
        ^:mut {\0     0
               \1     1
               \2     2
               \3     3
               \4     4
               \5     5
               \6     6
               \7     7
               \8     8
               \9     9
               \u0660 0
               \u0661 1
               \u0662 2
               \u0663 3
               \u0664 4
               \u0665 5
               \u0666 6
               \u0667 7
               \u0668 8
               \u0669 9
               \u09E6 0
               \u09E7 1
               \u09E8 2
               \u09E9 3
               \u09EA 4
               \u09EB 5
               \u09EC 6
               \u09ED 7
               \u09EE 8
               \u09EF 9}]
    m))

(def ^{:t {:map [:char :int]}} digitsMap (getDigitsMap))

(defn parse ^Integer [^String s]
  (let [result (atom (int 0))
        ^{:t :int} strLength (count s)]
    (dotimes [i strLength]
      (let [^{:t :char} digit (nth s i)]
        (if (contains? digitsMap digit)
          (let [^Integer digitVal (get digitsMap digit)]
            (reset! result (+ (* 10 @result) digitVal))))))
    @result))

(defn getNumberSystemsMap
  ^{:t {:map [:string {:list [:char]}]}} []
  (let [^{:t {:map [:string {:list [:char]}]}} m
        ^:mut {"LATIN"   ^:mut [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9]
               "ARABIC"  ^:mut [\u0660 \u0661 \u0662 \u0663 \u0664 \u0665 \u0666 \u0667 \u0668 \u0669]
               "BENGALI" ^:mut [\u09E6 \u09E7 \u09E8 \u09E9 \u09EA \u09EB \u09EC \u09ED \u09EE \u09EF]}]
    m))

(def ^{:t {:map [:string {:list [:char]}]}}
  numberSystemsMap (getNumberSystemsMap))

(defn getGroupingSeparatorsMap
  ^{:t {:map [:string :char]}} []
  (let [^{:t {:map [:string :char]}} m
        ^:mut {"LATIN"   \,
               "ARABIC"  \٬
               "BENGALI" \,}]
    m))

(def ^{:t {:map [:string :char]}}
  groupingSeparatorsMap (getGroupingSeparatorsMap))

(defn getSeparatorPositions ^{:t {:list [:int]}}
  [^Integer numLength ^String groupingStrategy]
  (let [^{:t {:list [:int]}} result (atom ^:mut [])]
    (cond
      (= groupingStrategy "NONE")
      @result

      (= groupingStrategy "ON_ALIGNED_3_3")
      (let [^{:t :int} i (atom (- numLength 3))]
        (while (< 0 @i)
          (swap! result conj @i)
          (reset! i (- @i 3)))
        @result)

      (= groupingStrategy "ON_ALIGNED_3_2")
      (let [^{:t :int } i (atom (- numLength 3))]
        (while (< 0 @i)
          (swap! result conj @i)
          (reset! i (- @i 2)))
        @result)

      (= groupingStrategy "MIN_2")
      (if (<= numLength 4)
        @result
        (let [^{:t :int} i (atom (- numLength 3))]
          (while (< 0 @i)
            (swap! result conj @i)
            (reset! i (- @i 3)))
          @result))

      true
      @result)))

(defn format
  ^String [^Integer num, ^String numberSystem, ^String groupingStrategy]
  (let [^{:t :int} i (atom num)
        ^StringBuffer result (StringBuffer.)]
    (while (not (= @i 0))
      (let [^Integer quotient (quot @i 10)
            ^Integer remainder (rem @i 10)
            ^{:t {:list [:char]}} numberSystemDigits (get numberSystemsMap numberSystem)
            ^Character localDigit (get numberSystemDigits remainder)]
        (.insert result 0 localDigit)
        (reset! i quotient)))
    ;; TODO: replace get with nth
    (let [^{:t :char} sep (get groupingSeparatorsMap numberSystem)
          ^{:t :int} numLength (.length result)
          ^{:t {:list [:int]}} separatorPositions (getSeparatorPositions numLength groupingStrategy)
          ;; TODO: replace .size with count
          ^{:t :int} numPositions (.size separatorPositions)]
      (dotimes [idx numPositions]
        ;; TODO: replace get with nth
        (let [^{:t :int} position (get separatorPositions idx)]
          (.insert result position sep))))
    (.toString result)))
