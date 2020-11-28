(ns kalai.pass.python.b-function-call
  (:require [kalai.util :as u]
            [meander.strategy.epsilon :as s]))

(def rewrite
  (s/bottom-up
    (s/rewrite
      (p/construct StringBuffer)
      (p/construct String)

      (p/method append (u/of-tag StringBuffer ?this) ?x)
      (p/operator + ?this ?x)

      (p/method length (u/of-tag StringBuffer ?this))
      (p/invoke len ?this)

      (p/method toString (u/of-tag StringBuffer ?this))
      ?this

      (p/method insert (u/of-tag StringBuffer ?this) ?idx ?s2)
      (p/block
        (m/let [t (u/tmp StringBuffer)]
               (p/assign t ?this)
               (p/invoke truncate t ?idx)
               (p/operator + t ?s2))))))
