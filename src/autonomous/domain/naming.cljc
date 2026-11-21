(ns autonomous.domain.naming
  "Names agents see: tool names of [A-Za-z0-9_-], 1..64 characters, from
   application ids that are namespaced keywords; and actors as the library
   shows them, printable ASCII. Pure.")

(def max-length 64)

(def ^:private name-chars
  (set (seq "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")))

(defn valid-tool-name?
  "True when `s` is a legal MCP tool name."
  [s]
  (and (string? s)
       (<= 1 (count s) max-length)
       (every? name-chars (seq s))))

(defn sanitize
  "`s` with every character (UTF-16 code unit) outside [A-Za-z0-9_-]
   replaced by _. The length is unchanged."
  [s]
  (apply str (map #(if (name-chars %) % "_") (str s))))

(def max-shown-actor
  "The most characters of a shown actor: printable ASCII, so also the most
   bytes."
  64)

(defn- units
  "The UTF-16 code units of `s`, as ints. A runtime whose strings hold code
   points (jolt) yields an astral character as its surrogate pair, so every
   runtime sees the same units."
  [s]
  (mapcat (fn [ch]
            (let [c #?(:cljs (.charCodeAt ch 0) :default (int ch))]
              (if (< c 0x10000)
                [c]
                (let [v (- c 0x10000)]
                  [(+ 0xD800 (bit-shift-right v 10)) (+ 0xDC00 (bit-and v 0x3FF))]))))
          (seq (str s))))

(defn- hex4 [c]
  (apply str (map #(nth "0123456789abcdef" (bit-and (bit-shift-right c %) 15)) [12 8 4 0])))

(defn- shown-unit
  "One code unit as shown: printable ASCII but \" and \\ as itself, anything
   else as \\uxxxx."
  [c]
  (if (and (<= 0x20 c 0x7E) (not= c 0x22) (not= c 0x5C))
    (str (char c))
    (str "\\u" (hex4 c))))

(defn- shown-chars
  "The shown text of `actor`, one string per character: one escape or ASCII
   character per code unit, except that a surrogate pair (an astral
   character) is one item of two escapes, so no cut can split it."
  [actor]
  (loop [[u & more :as us] (units actor) out []]
    (cond
      (empty? us) out
      (and (<= 0xD800 u 0xDBFF) more (<= 0xDC00 (first more) 0xDFFF))
      (recur (rest more) (conj out (str (shown-unit u) (shown-unit (first more)))))
      :else (recur more (conj out (shown-unit u))))))

(defn actor-text
  "`actor` escaped as shown-actor escapes it, without the cut."
  [actor]
  (apply str (shown-chars actor)))

(defn shown-actor
  "`actor` as the library shows it to a human or to a host, the one rule
   every place that shows an actor uses. Only printable ASCII (U+0020..
   U+007E) other than \" and \\ is shown as itself. Those two and every
   other UTF-16 code unit become \\uxxxx (lowercase hex): a control, bidi
   or zero-width character becomes visible, and an astral character becomes
   its surrogate pair (\\ud83d\\ude00), on every runtime. The text is safe
   inside quotes, and two distinct actors have distinct actor-text.

   The result is cut at max-shown-actor characters, never inside an escape
   or between the two halves of a pair. A cut actor may show the same as
   another one; actor-too-long? says when the cut happens, and a host is
   never handed a cut actor (autonomous.domain.decision/host-grant). nil
   shows as \"\"."
  [actor]
  (loop [[t & more] (shown-chars actor) out ""]
    (if (and t (<= (+ (count out) (count t)) max-shown-actor))
      (recur more (str out t))
      out)))

(defn actor-too-long?
  "True when `actor`'s shown form (actor-text) is longer than
   max-shown-actor characters, so shown-actor would cut it."
  [actor]
  (> (count (actor-text actor)) max-shown-actor))

(def ^:private invisible-units
  "Code units that show nothing: whitespace, C0, DEL and C1 controls,
   zero-width characters, bidi controls, separators and the BOM."
  (into #{0x85 0xA0 0x1680 0x180E 0x2028 0x2029 0x202F 0x205F 0x3000 0xFEFF}
        (concat (range 0x00 0x21) (range 0x7F 0xA0) (range 0x2000 0x2010)
                (range 0x202A 0x202F) (range 0x2060 0x2070))))

(defn nameless-actor?
  "True when `actor` names no one: nil, empty, or only characters that show
   nothing (invisible-units). Such an actor is refused, not shown."
  [actor]
  (every? invisible-units (units actor)))

(defn tool-name
  "MCP-safe name for a tool id: :todo/add is \"todo_add\", :app.todos/add-item
   is \"app_todos_add-item\". Every character outside [A-Za-z0-9_-] becomes _,
   and the result is cut at 64 characters. An empty id names \"_\". Always a
   valid tool name; a valid name maps to itself."
  [id]
  (let [s   (if (keyword? id) (subs (str id) 1) (str id))
        out (sanitize (apply str (take max-length s)))]
    (if (= "" out) "_" out)))

(def max-target-id-length 48)

(def ^:private target-id-chars (conj name-chars \.))

(defn valid-target-id?
  "True when `s` can name a target of a fleet: 1..48 of [A-Za-z0-9._-]."
  [s]
  (and (string? s)
       (<= 1 (count s) max-target-id-length)
       (every? target-id-chars (seq s))))
