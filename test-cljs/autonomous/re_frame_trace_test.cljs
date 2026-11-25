(ns autonomous.re-frame-trace-test
  "Tracing in a real ClojureScript runtime: an agent call, the re-frame
   event it dispatches, the events that event dispatches, and an event
   that throws, as one trace of OpenTelemetry-shaped spans."
  (:require [autonomous.core :as a]
            [autonomous.domain.trace :as dt]
            [autonomous.runtime.trace :as trace]
            [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [todo.agentic :as agentic]
            [todo.model]))

(rf/reg-event-fx ::parent (fn [_ _] {:fx [[:dispatch [::child 1]]]}))
(rf/reg-event-db ::child (fn [db [_ n]] (assoc db ::child n)))
(rf/reg-event-db ::boom (fn [_ _] (throw (ex-info "boom" {:why :test}))))

(defn- fresh-app! []
  (a/reset-registry!)
  (rf/dispatch-sync [:todo/init])
  (agentic/install! true))

(use-fixtures :each {:before fresh-app!})

(def agent {:actor "agent-1" :scopes [:read :write]})

(defn- by-type [t] (filter #(= t (:type %)) (trace/dump)))

(deftest an-agent-call-is-the-parent-of-the-event-it-dispatches
  (is (trace/enabled?) "install-tracing! turns tracing on")
  (a/rpc (merge agent {:op :call :tool "todo_add" :args {:text "milk"}}))
  (let [[call] (by-type :agent/call)
        event  (first (filter #(= ":todo/add" (:name %)) (by-type :re-frame/event)))]
    (is (some? call))
    (is (some? event))
    (is (= (:trace-id call) (:trace-id event)) "one trace")
    (is (= (:span-id call) (:parent-id event)) "the event is the call's child")
    (is (= "agent-1" (:actor call)))
    (is (= :todo/add (get-in event [:attrs :re-frame.event.id])))
    (is (some #(= [:todos 1] %) (get-in event [:attrs :re-frame.db.changed]))
        "the span names what the event changed in app-db")
    (is (= :ok (:status event)))))

(deftest a-dispatched-event-is-the-child-of-the-event-that-dispatched-it
  (async done
    (rf/dispatch-sync [::parent])
    (js/setTimeout
     (fn []
       (let [p (first (filter #(= (str ::parent) (:name %)) (by-type :re-frame/event)))
             c (first (filter #(= (str ::child) (:name %)) (by-type :re-frame/event)))]
         (is (some? c) "the dispatched event ran and was traced")
         (is (= (:trace-id p) (:trace-id c)))
         (is (= (:span-id p) (:parent-id c)))
         (is (seq (get-in p [:attrs :re-frame.dispatched])) "the parent names what it dispatched"))
       (done))
     50)))

(deftest a-throwing-event-ends-its-span-as-an-error
  (try (rf/dispatch-sync [::boom]) (catch :default _ nil))
  (let [s (first (filter #(= (str ::boom) (:name %)) (by-type :re-frame/event)))]
    (is (= :error (:status s)))
    (is (= "boom" (get-in s [:error :message])))
    (testing "findings name it"
      (is (some #(and (= :failed (:finding %)) (= [(str ::boom)] (:path %)))
                (dt/findings (trace/dump)))))))

(deftest a-mark-starts-a-new-story
  (let [id (trace/mark! "test: mark")]
    (is (= id (:trace-id (first (by-type :test/mark)))))
    (is (nil? (:parent-id (first (by-type :test/mark)))))))

(deftest the-otlp-export-of-a-page-is-well-formed
  (a/rpc (merge agent {:op :call :tool "todo_add" :args {:text "x"}}))
  (let [otlp  (dt/->otlp (trace/dump) "todo")
        spans (mapcat :spans (mapcat :scopeSpans (:resourceSpans otlp)))]
    (is (seq spans))
    (is (every? #(= 32 (count (:traceId %))) spans))
    (is (every? #(= 16 (count (:spanId %))) spans))))
