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

(defn- resolve [bundle node] (odoc/resolved bundle node))

(defn- resolved-map
  "The map a document node resolves to, or nil for a malformed node."
  [bundle node]
  (let [resolved (resolve bundle node)]
    (when (map? resolved) resolved)))

(defn- message-view
  "The tap-facing view of one resolved message map."
  [mname m]
  {:name mname
   :content-type (get m "contentType")
   :schema (some-> (get m "payload") schema/->malli)})

(defn- address-of
  "Subject of channel `id` resolved to `ch`: its 3.x `address`, else the key."
  [id ch]
  (let [a (get ch "address")]
    (if (string? a) a id)))

(defn- message
  "Message map of name `mname` resolved to `m`, or nil when it is not one."
  [bundle mname m]
  (some->> (resolved-map bundle m) (message-view mname)))

(defn- messages
  "Every message of channel `ch`, unresolved refs inlined."
  [bundle ch]
  (into [] (keep (fn [[mname m]] (message bundle mname m))) (get ch "messages")))

(defn- channel-of
  "Channel of entry `[id ch]`, or nil for a malformed channel node."
  [bundle [id ch]]
  (when-let [ch (resolved-map bundle ch)]
    {:channel-id id
     :address (address-of id ch)
     :description (get ch "description")
     :messages (messages bundle ch)}))

(defn channels
  "Every channel of `bundle` as {:channel-id :address :description :messages},
   in document order. :messages entries carry :name, :content-type and a
   malli :schema, nil when the message declares no payload."
  [bundle]
  (into [] (keep #(channel-of bundle %))
        (get (odoc/root-doc bundle) "channels")))

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
  (some :schema (:messages (get (by-address bundle) address))))

(defn channel
  "Ok channel of `address`, or a refusal naming the declared subjects."
  [bundle address]
  (if-let [c (get (by-address bundle) address)]
    (answer/ok c)
    (answer/refusal :asyncapi/unknown-subject
                    (str (pr-str address) " is not a channel address of the document")
                    {:known (addresses bundle)})))
