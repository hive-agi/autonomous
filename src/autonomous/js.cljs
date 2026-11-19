(ns autonomous.js
  "The runtime for JavaScript apps that are not ClojureScript (plain JS,
   TypeScript, Elm through ports, ...). Built as an ES module by the
   shadow-cljs build :js:

     import * as autonomous from './autonomous.js';

     autonomous.setApp({name: 'todo', description: 'A todo list UI.'});
     autonomous.registerTool('ui/submit',
       {description: 'Submit the draft, as pressing Enter does.', input: '[:map]'},
       (args, grant) => elmCommand('submit'));          // a value or a Promise
     autonomous.connect({url: 'ws://127.0.0.1:7890/autonomous/ws', token: 'aut_...'});

   `input` is a malli schema written as EDN text, the form the C ABI uses
   too. Arguments reach the handler validated, as a plain object; the grant
   is {actor, scopes}. A handler may answer a Promise: the bridge polls
   until it settles."
  (:require [autonomous.core :as a]
            [autonomous.domain.wire :as wire]
            [autonomous.ws :as ws]))

(defn- js-spec->spec
  "A ToolSpec from the JS spec object `o` and `handler`."
  [id o handler]
  (let [o (js->clj o :keywordize-keys true)]
    (cond-> {:description (:description o)
             :input       (if (string? (:input o)) (wire/read-edn (:input o)) [:map])
             :handler     (fn [args grant]
                            (handler (clj->js args)
                                     (clj->js {:actor (:actor grant)
                                               :scopes (mapv name (:scopes grant))})))}
      (contains? o :readOnly) (assoc :read-only? (boolean (:readOnly o)))
      (:title o)              (assoc :title (:title o))
      (seq (:scopes o))       (assoc :scopes (set (map keyword (:scopes o)))))))

(defn ^:export setApp
  "Describe the app: {name, description, version}."
  [info]
  (a/set-app-info! (js->clj info :keywordize-keys true))
  nil)

(defn ^:export registerTool
  "Register tool `id` (\"ui/submit\", MCP name ui_submit). Answers the MCP
   name; throws on an invalid spec."
  [id spec handler]
  (a/reg-tool (keyword id) (js-spec->spec id spec handler)))

(defn ^:export unregisterTool [id]
  (a/unreg-tool (keyword id))
  nil)

(defn ^:export journal
  "Record an app event agents can read: {kind: 'todo/added', ...}."
  [entry]
  (let [e (js->clj entry :keywordize-keys true)]
    (a/journal! (update e :kind keyword))))

(defn ^:export connect
  "Dial the bridge and keep the connection up: {url, token, runtimeId,
   label, onStatus(status, info)}. Answers {runtimeId, status(), stop()}."
  [opts]
  (let [o (js->clj opts :keywordize-keys true)
        h (ws/connect! (cond-> {:url (:url o) :token (:token o)}
                         (:runtimeId o) (assoc :runtime-id (:runtimeId o))
                         (:label o)     (assoc :label (:label o))
                         (:onStatus o)  (assoc :on-status (fn [s info] ((:onStatus o) (name s) (clj->js info))))))]
    #js {:runtimeId (:runtime-id h)
         :status    (fn [] (name ((:status h))))
         :stop      (fn [] ((:stop! h)) nil)}))
