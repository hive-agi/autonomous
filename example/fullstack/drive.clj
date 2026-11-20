(ns drive
  "Drive the whole fullstack todo app from Clojure, through the bridge.

     AUTONOMOUS_MCP=http://127.0.0.1:7891/mcp AUTONOMOUS_TOKEN=aut_... bb fullstack-drive [--step-ms 1200]

   The same MCP calls an agent makes: the backend's data tools
   (backend__todos_*) and the UI's tools (frontend__ui_*), which run through
   Elm's update in the open tab, so a person watching the page sees every
   step. `check!` is the same story with assertions, for bb fullstack-e2e."
  (:require [autonomous.e2e.agents :as agents]
            [autonomous.e2e.scenario :as scenario]
            [clojure.string :as str]))

(defn- unwrap
  "The tool's value: structured content, unwrapped when a non-object
   result was boxed for MCP."
  [{:keys [value]}]
  (if (and (map? value) (= 1 (count value)) (contains? value :result)) (:result value) value))

(defn call
  "Call `tool` with `args` as `agent`: {:value v :error? bool}."
  [agent tool args]
  (let [r (scenario/-call agent tool args)]
    (assoc r :value (unwrap r))))

(defn connect
  "An MCP session on the bridge at `url` with bearer `token`."
  [url token]
  (agents/http-session! (agents/http-client) url token))

(defn- wait-until [timeout-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 150) (recur))))))

(defn apps-up?
  "True when both apps have a runtime connected to the bridge."
  [agent]
  (let [apps (:value (call agent "autonomous_apps" {}))
        up   (->> (or (:apps apps) apps) (filter #(= "up" (some-> (:status %) name))) (map :target/id) set)]
    (or (every? up ["backend" "frontend"])
        ;; tolerate a differently shaped status: probe both apps directly
        (and (not (:error? (call agent "backend__todos_list" {})))
             (not (:error? (call agent "frontend__ui_state" {})))))))

(defn texts [todos] (set (map :text todos)))

(defn story
  "The scripted session, as [label tool args] steps."
  []
  [["The agent adds a todo straight into the Rust backend" "backend__todos_add" {:text "buy oat milk"}]
   ["...and types one into the Elm UI, as a person would" "frontend__ui_type" {:text "walk the dog"}]
   ["...then presses Enter in the UI" "frontend__ui_submit" {}]
   ["What does the person see now?" "frontend__ui_state" {}]
   ["The agent reads the backend's list" "backend__todos_list" {}]])

(defn- show [v] (let [s (pr-str v)] (if (< 160 (count s)) (str (subs s 0 157) "...") s)))

(defn -main [& args]
  (let [url     (or (System/getenv "AUTONOMOUS_MCP") "http://127.0.0.1:7891/mcp")
        token   (System/getenv "AUTONOMOUS_TOKEN")
        step-ms (or (some-> (second (drop-while #(not= "--step-ms" %) args)) parse-long) 1200)
        agent   (connect url token)]
    (when-not (wait-until 30000 #(apps-up? agent))
      (println "Both apps must be connected: is the page open in a browser?")
      (System/exit 1))
    (doseq [[label tool targs] (story)]
      (println (str "\n" label "\n  " tool " " (pr-str targs)))
      (println "  =>" (show (:value (call agent tool targs))))
      (Thread/sleep (long step-ms)))
    (let [todos (:value (call agent "backend__todos_list" {}))
          dog   (first (filter #(= "walk the dog" (:text %)) todos))]
      (when dog
        (println "\nThe agent ticks \"walk the dog\" in the UI and shows only active todos")
        (call agent "frontend__ui_click_toggle" {:id (:id dog)})
        (Thread/sleep (long step-ms))
        (println "  =>" (show (:value (call agent "frontend__ui_set_filter" {:filter "active"}))))))
    (println "\nDone. Every step went through the same paths a person's clicks take.")))

(defn check!
  "The story with assertions; answers a seq of problems (empty when it held)."
  [url token]
  (let [agent    (connect url token)
        problems (atom [])
        expect   (fn [ok? what] (when-not ok? (swap! problems conj what)))]
    (expect (wait-until 60000 #(apps-up? agent)) "both apps connect to the bridge")
    (let [added (call agent "backend__todos_add" {:text "buy oat milk"})]
      (expect (= "buy oat milk" (get-in added [:value :text])) (str "backend add answers the todo: " (pr-str added))))
    (expect (wait-until 10000 #(contains? (texts (get-in (call agent "frontend__ui_state" {}) [:value :visible])) "buy oat milk"))
            "a backend change reaches the UI (server-sent events into Elm)")
    (call agent "frontend__ui_type" {:text "walk the dog"})
    (expect (= "walk the dog" (get-in (call agent "frontend__ui_state" {}) [:value :draft])) "typing goes through Elm's update")
    (call agent "frontend__ui_submit" {})
    (expect (wait-until 10000 #(contains? (texts (:value (call agent "backend__todos_list" {}))) "walk the dog"))
            "submitting in the UI reaches the Rust backend over REST")
    (expect (:error? (call agent "backend__todos_add" {:text ""})) "the backend's schema refuses an empty todo")
    (expect (:error? (call agent "frontend__ui_set_filter" {:filter "someday"})) "the UI's schema refuses an unknown filter")
    (let [dog (first (filter #(= "walk the dog" (:text %)) (:value (call agent "backend__todos_list" {}))))]
      (call agent "frontend__ui_click_toggle" {:id (:id dog)})
      (expect (wait-until 10000 #(true? (:done (first (filter (fn [t] (= (:id dog) (:id t))) (:value (call agent "backend__todos_list" {}))))))) 
              "a click in the UI toggles the todo in the backend")
      (call agent "frontend__ui_set_filter" {:filter "active"})
      (let [st (:value (call agent "frontend__ui_state" {}))]
        (expect (= "active" (:filter st)) "the filter changed in the UI")
        (expect (= #{"buy oat milk"} (texts (:visible st))) (str "the active filter hides the done todo: " (pr-str st)))))
    (expect (= ["walk the dog"] (mapv :text (:value (call agent "backend__todos_list" {:filter "done"}))))
            "the backend's own filter agrees")
    @problems))
