(ns autonomous.asyncapi.rail
  "The Result railway, for the few places where several Answers chain. Pure.

   autonomous.domain.answer already has the three shapes (Ok, Pending,
   Refusal); what it lacks is a way to say \"then\" without a hand-written
   refused? staircase at every step. That is all this namespace is:

     (rail/bind (check-a) (fn [a] (step-b a)))   ; a refusal short-circuits
     (rail/map-ok answer f)                      ; f only on an Ok
     (rail/collect a b)                          ; a vector of Oks, or the
                                                 ;   first refusal

   One return type per function: every fn here answers an Answer, or a vector
   of them in `collect`'s case, never a bare value and never a thrown
   exception."
  (:require [autonomous.domain.answer :as answer]))

(defn bind
  "`f` applied to Ok `a`'s value; a refusal or a Pending is returned as is."
  [a f]
  (if (answer/ok? a) (f (:ok a)) a))

(defn map-ok
  "Ok of `f` applied to Ok `a`'s value; anything else unchanged."
  [a f]
  (bind a (comp answer/ok f)))

(defn ok-values
  "The values of an Ok answer holding a sequential, or nil."
  [a]
  (when (answer/ok? a) (:ok a)))

(defn collect
  "Sequence of Answers into an Ok vector of their values, or the first
   refusal (which is what a caller wants: the earliest reason)."
  [answers]
  (reduce (fn [acc a]
            (if (answer/refused? a)
              (reduced a)
              (update acc :ok conj (:ok a))))
          (answer/ok [])
          answers))
