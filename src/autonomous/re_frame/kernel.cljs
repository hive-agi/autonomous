(ns autonomous.re-frame.kernel
  "What the re-frame and re-frame2 adapters share: event and subscription
   tools, their codecs, the declarations Source and the app-db resource,
   built over a Host of ports. Each adapter namespace supplies only its Host,
   so neither one loads the other's re-frame.

   Host:
     :frames?        does this re-frame have frames (re-frame2)
     :registered?    (fn [kind id frame]) is a handler registered
     :dispatch       (fn [event frame]) enqueue; frame nil is the ambient one
     :dispatch-sync  (fn [event frame])
     :read-sub       (fn [query frame]) one read of a subscription
     :app-db         (fn [frame]) the app-db value
     :check-frame    (fn [frame]) throw unless frame can be used now"
  (:require [autonomous.core :as core]
            [autonomous.domain.answer :as answer]
            [autonomous.domain.wire :as wire]
            [autonomous.re-frame.model :as model]
            [autonomous.surface :as surface])
  (:require-macros [autonomous.domain.answer :as answer]))

(defn frame-refusal
  "nil when `frame` may be declared on `host`, else the refusal saying why."
  [host frame]
  (cond
    (nil? frame) nil
    (model/frame-problem frame)
    (answer/refusal :invalid-tool (model/frame-problem frame) {:frame frame})
    (not (:frames? host))
    (answer/refusal :re-frame/no-frames
                    (str "the :frame option " (pr-str frame) " needs re-frame2; use autonomous.re-frame2")
                    {:frame frame})))

(defn checked-frame!
  "`frame`, or throw the refusal `frame-refusal` answers for it."
  [host frame]
  (when-let [r (frame-refusal host frame)]
    (throw (ex-info (get-in r [:error :message])
                    (assoc (get-in r [:error :data]) :autonomous/error (get-in r [:error :type])))))
  frame)

(defn- usable-frame
  "The tool's frame, after the host confirms it can be used for this call."
  [host frame]
  (when frame ((:check-frame host) frame))
  frame)

(defn- refuse-unregistered [kind id]
  (throw (ex-info (str "no re-frame " (name kind) " handler registered for " id)
                  {:autonomous/error :unregistered-handler :kind kind :id id})))

(defn- result-of
  "What an event tool answers after its dispatch."
  [host tool args event frame]
  (cond
    (:result tool)      ((:result tool) ((:app-db host) frame) args)
    (:result-sub tool)  (let [q (:result-sub tool)] ((:read-sub host) (if (fn? q) (q args) q) frame))
    (:result-path tool) (get-in ((:app-db host) frame) (:result-path tool))
    :else               {:dispatched (wire/->wire event)}))

(defn event-call
  "L3 Pipeline: dispatch an encoded event in the tool's frame and produce
   its declared result."
  [host tool args event grant]
  (let [frame (usable-frame host (:frame tool))]
    (when-not ((:registered? host) :event (first event) frame)
      (refuse-unregistered :event (first event)))
    (if (:async? tool)
      (do ((:dispatch host) (model/tag-event event grant) frame) {:queued (wire/->wire event)})
      (do ((:dispatch-sync host) (model/tag-event event grant) frame)
          (result-of host tool args event frame)))))

(defn subscription-call
  "L3 Pipeline: read an encoded query in the tool's frame."
  [host tool query]
  (let [frame (usable-frame host (:frame tool))]
    (when-not ((:registered? host) :sub (first query) frame)
      (refuse-unregistered :sub (first query)))
    ((:read-sub host) query frame)))

(def event-codec
  "L3 Codec for a re-frame event tool: encode {:event :args}; dispatch
   output becomes its declared result."
  {:encode (fn [tool args]
             (if (fn? (:args->event tool))
               (answer/ok {:event ((:args->event tool) args) :args args})
               (answer/refusal :re-frame/invalid-call "event tool needs :args->event")))
   :decode (fn [_ value] (answer/ok value))})

(def subscription-codec
  "L3 Codec for a re-frame subscription tool: args become a query; the
   subscription's value is the decoded result."
  {:encode (fn [tool args]
             (if (fn? (:args->query tool))
               (answer/ok ((:args->query tool) args))
               (answer/refusal :re-frame/invalid-call "subscription tool needs :args->query")))
   :decode (fn [_ value] (answer/ok value))})

(defn- exposed-specs
  "L3 Collect: only explicitly exposed event and subscription declarations."
  [{:keys [event-kind sub-kind]} {:keys [events subs]}]
  (concat (map (fn [[id spec]] [id (assoc spec :kind event-kind)])
               (filter (comp :expose val) events))
          (map (fn [[id spec]] [id (assoc spec :kind sub-kind :read-only? true)])
               (filter (comp :expose val) subs))))

(defn- declaration-specs
  "L3 Promote: exposed declarations as ToolSpecs; a :frame the host cannot
   take refuses the whole source."
  [host kinds source]
  (let [specs (exposed-specs kinds source)]
    (or (some (fn [[id spec]]
                (some-> (frame-refusal host (:frame spec)) (assoc-in [:error :data :tool] (str id))))
              specs)
        (surface/compile-tools specs))))

(defn adapters
  "The three L3 adapter declarations for `host`: the declarations Source
   and the event and subscription Codecs with their host ports. `kinds`
   names them: {:source-kind :event-kind :sub-kind}."
  [host {:keys [source-kind event-kind sub-kind] :as kinds}]
  {:source {:source-kind source-kind
            :derive (fn [source opts]
                      (answer/let-answer [tools (declaration-specs host kinds source)]
                        (surface/->surface {:app (:app opts {:name "app"}) :tools tools})))}
   :event  {:kind event-kind :codec event-codec
            :invoke (fn [tool {:keys [event args]} grant]
                      (answer/ok (event-call host tool args event grant)))}
   :sub    {:kind sub-kind :codec subscription-codec
            :invoke (fn [tool query _grant]
                      (answer/ok (subscription-call host tool query)))}})

(defn reg-event-tool
  "Register event `event-id` as a tool of `kind`; see autonomous.re-frame."
  [host kind event-id spec]
  (checked-frame! host (:frame spec))
  (core/reg-tool (or (:tool-id spec) event-id)
                 (merge (dissoc spec :tool-id)
                        {:kind kind
                         :args->event (or (:args->event spec) #(model/default-event event-id %))})))

(defn reg-sub-tool
  "Register subscription `query-id` as a read-only tool of `kind`."
  [host kind query-id spec]
  (checked-frame! host (:frame spec))
  (core/reg-tool (or (:tool-id spec) query-id)
                 (merge {:read-only? true}
                        (dissoc spec :tool-id)
                        {:kind kind
                         :args->query (or (:args->query spec) #(model/default-query query-id %))})))

(defn expose-db!
  "Register the app-db of `frame` (nil: the host's default) as a resource."
  [host {:keys [uri paths redact scopes description frame]}]
  (checked-frame! host frame)
  (core/reg-resource (or uri "re-frame://app-db")
                     (cond-> {:name "app-db"
                              :description (or description "The re-frame application state")
                              :read (fn [_] (model/db-view ((:app-db host) (usable-frame host frame))
                                                           paths redact))}
                       scopes (assoc :scopes scopes))))
