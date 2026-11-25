(ns autonomous.trace.dom
  "Pure half of browser capture (autonomous.trace.browser): how an element
   is described on a span, what a change records, how a request ends.
   cljc, so it is tested on the JVM with no DOM."
  ;; pattern-exempt: the autonomous runtime ships inside third-party browser
  ;; bundles and depends on malli only (doc/architecture.md), so it cannot
  ;; take hive-system.pattern; the one regex here is whitespace splitting.
  (:require [clojure.string :as str]))

(defn clip
  "`s` cut to `n` characters, with an ellipsis when cut."
  [s n]
  (when s (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- words [s]
  (remove str/blank? (str/split (str s) #"\s+")))

(defn selector
  "A short CSS-like path of element facts `props`: up to three ancestors,
   then the element with its id and first two classes."
  [{:keys [tag id classes parents]}]
  (let [one (fn [{:keys [tag id classes]}]
              (str (some-> tag str/lower-case)
                   (when id (str "#" id))
                   (apply str (map #(str "." %) (take 2 (words classes))))))]
    (str/join " > " (concat (map one (reverse parents)) [(one {:tag tag :id id :classes classes})]))))

(declare safe-url)

(defn describe
  "Span attributes of an element (`props` as autonomous.trace.browser
   reads them): tag, selector, visible text (60 characters), test id, role,
   label, name, link. Nothing typed into it."
  [props]
  (when props
    (cond-> {:ui.element.tag      (some-> (:tag props) str/lower-case)
             :ui.element.selector (selector props)}
      (seq (words (:text props))) (assoc :ui.element.text (clip (str/join " " (words (:text props))) 60))
      (:testid props)     (assoc :ui.element.testid (:testid props))
      (:role props)       (assoc :ui.element.role (:role props))
      (:aria-label props) (assoc :ui.element.label (:aria-label props))
      (:name props)       (assoc :ui.element.name (:name props))
      (:type props)       (assoc :ui.element.type (:type props))
      (:href props)       (assoc :url.full (safe-url (:href props) false)))))

(defn ui-span-name
  "\"click button 'Save'\", \"submit form#new\"."
  [type props]
  (let [d (describe props)]
    (str type " " (or (:ui.element.testid d)
                      (when-let [t (:ui.element.text d)] (str (:ui.element.tag d) " '" (clip t 30) "'"))
                      (:ui.element.selector d)
                      "?"))))

(defn change-attrs
  "What a change event records: never the value, only its length (and not
   that for a password), or the checked state of a box."
  [props value checked]
  (let [type (some-> (:type props) str/lower-case)]
    (cond
      (#{"checkbox" "radio"} type) {:ui.checked (boolean checked)}
      (= "password" type)          {}
      :else                        {:ui.value.length (count (str value))})))

(defn url-path
  "The path of `url` (no scheme, host, query or fragment), for span names."
  [url]
  (let [s    (str url)
        s    (if-let [i (str/index-of s "://")]
               (let [rest (subs s (+ i 3))] (if-let [j (str/index-of rest "/")] (subs rest j) ""))
               s)
        cut  (keep #(str/index-of s %) ["?" "#"])
        s    (if (seq cut) (subs s 0 (apply min cut)) s)]
    (if (str/blank? s) "/" s)))

(defn safe-url
  "`url` as a span records it: without its query string and fragment
   (where tokens travel) unless `payloads?`."
  [url payloads?]
  (let [s (str url)]
    (if payloads?
      s
      (let [cut (keep #(str/index-of s %) ["?" "#"])]
        (if (seq cut) (subs s 0 (apply min cut)) s)))))

(defn client-outcome
  "How an :http/client span ends for HTTP `status`: an error from 400 up,
   as the OpenTelemetry HTTP conventions have it for clients."
  [status]
  (if (and (number? status) (>= status 400))
    {:status :error :error {:message (str "HTTP " status)}
     :attrs {:http.response.status_code status :error.type (str status)}}
    {:attrs {:http.response.status_code status}}))
