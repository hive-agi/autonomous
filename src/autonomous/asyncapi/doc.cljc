(ns autonomous.asyncapi.doc
  "An AsyncAPI document as the channels a tap can subscribe to. Pure.

   A document is normalized and $ref-resolved by autonomous.openapi.doc (a
   Bundle), so a payload schema in a sibling JSON file and a message in
   components are both inlined before anything reads them. This namespace
   only says which subject each channel is on and what its messages must
   look like; it does no IO.

   AsyncAPI 3 addresses a channel with `address` and a 2.x document uses the
   channel KEY as the subject, so both read the same way here."
  (:require [autonomous.domain.answer :as answer]
            [autonomous.openapi.doc :as odoc]
            [autonomous.openapi.schema :as schema]))

(defn channels
  "Every channel of `bundle` as {:address :channel-id :description :messages},
   in document order.
     :messages [{:name :content-type :schema}] with :schema a malli form, nil
   when the message declares no payload."
  [bundle]
  (let [root (odoc/root-doc bundle)]
    (for [[id ch] (get root "channels")
          :when (map? ch)
          :let [ch      (odoc/resolved bundle ch)
                address (let [a (get ch "address")] (if (string? a) a id))]]
      {:channel-id  id
       :address     address
       :description (get ch "description")
       :messages    (into []
                          (keep (fn [[mname m]]
                                  (when-let [m (some-> m (odoc/resolved bundle))]
                                    (when (map? m)
                                      {:name         mname
                                       :content-type (get m "contentType")
                                       :schema       (some-> (get m "payload") schema/->malli)}))))
                          (get ch "messages"))})))

(defn by-address
  "Channels of `bundle` keyed by their address; the first wins when two
   channels share one."
  [bundle]
  (reduce (fn [acc c] (if (contains? acc (:address c)) acc (assoc acc (:address c) c)))
          {} (channels bundle)))

(defn addresses
  "Every subject the document declares, sorted."
  [bundle]
  (vec (sort (map :address (channels bundle)))))

(defn schema-of
  "The malli form validating `address`'s payload: the first message that
   declares one, or nil when the channel is unknown or carries no payload
   (a wildcard tap over subjects the document does not name has none)."
  [bundle address]
  (some (fn [m] (:schema m)) (:messages (get (by-address bundle) address))))

(defn channel
  "Ok channel of `address`, or a refusal naming the declared subjects."
  [bundle address]
  (if-let [c (get (by-address bundle) address)]
    (answer/ok c)
    (answer/refusal :asyncapi/unknown-subject
                    (str (pr-str address) " is not a channel address of the document")
                    {:known (addresses bundle)})))
