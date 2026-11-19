(ns todo.browser
  "Browser entry: the same app with a reagent view. Open several tabs and
   attach a different agent to each (autonomous_attach)."
  (:require [re-frame.core :as rf]
            [reagent.dom.client :as rdc]
            [todo.agentic :as agentic]
            [todo.model]))

(defn- todo-item [{:keys [id text done?]}]
  [:li {:key id}
   [:input {:type "checkbox" :checked done? :on-change #(rf/dispatch [:todo/toggle id])}]
   [:span {:style {:text-decoration (when done? "line-through")}} " " text " "]
   [:button {:on-click #(rf/dispatch [:todo/remove id])} "x"]])

(defn- view []
  (let [draft (atom "")]
    (fn []
      (let [todos @(rf/subscribe [:todos/visible])
            {:keys [total done]} @(rf/subscribe [:todos/stats])]
        [:main
         [:h1 "todo"]
         [:form {:on-submit (fn [e] (.preventDefault e)
                              (when (seq @draft) (rf/dispatch [:todo/add @draft]) (reset! draft "")))}
          [:input {:placeholder "what needs doing?" :on-change #(reset! draft (.. % -target -value))}]]
         (into [:ul] (map todo-item) todos)
         [:p done " of " total " done"]]))))

(defonce ^:private root (delay (rdc/create-root (js/document.getElementById "app"))))

(defn ^:dev/after-load mount []
  (rdc/render @root [view]))

(defn init []
  (rf/dispatch-sync [:todo/init])
  (agentic/install! goog.DEBUG)
  (mount))
