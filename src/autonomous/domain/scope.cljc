(ns autonomous.domain.scope
  "Scope algebra. A Grant's scopes cover a required set when they contain
   every required scope, or contain :admin. Pure.")

(def admin :admin)

(defn normalize
  "Scopes as a set of keywords. Accepts keywords or strings, any collection;
   nil is the empty set."
  [xs]
  (into #{} (map keyword) xs))

(defn default-for
  "Scopes a tool requires when its author names none: reading needs :read,
   anything else :write."
  [read-only?]
  (if read-only? #{:read} #{:write}))

(defn allowed?
  "True when `granted` covers `required`. :admin covers every scope."
  [required granted]
  (or (contains? granted admin)
      (every? #(contains? granted %) required)))

(defn names
  "Sorted scope names, for messages and manifests."
  [scopes]
  (vec (sort (map name scopes))))
