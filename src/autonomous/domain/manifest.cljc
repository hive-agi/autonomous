(ns autonomous.domain.manifest
  "The Manifest: the affordances one Grant may use, as plain data an agent
   reads. Compiled functions never leave the runtime. Pure."
  (:require [autonomous.domain.scope :as scope]
            [autonomous.domain.wire :as wire]))

(def protocol-version
  "Version of the Request and Answer shapes. Bump on an incompatible change."
  1)

(defn describe-tool [t]
  (cond-> {:name         (:name t)
           :title        (or (:title t) (:name t))
           :description  (:description t)
           :input-schema (:json-schema t)
           :annotations  (:annotations t)
           :scopes       (scope/names (:scopes t))}
    (:output-schema t) (assoc :output-schema (:output-schema t))))

(defn describe-resource [r]
  (select-keys r [:uri :name :description :mime]))

(defn project
  "Manifest of `snapshot` for `grant`: tools and resources its scopes allow,
   sorted by name and uri."
  [snapshot grant]
  (let [ok? #(scope/allowed? (:scopes %) (:scopes grant))]
    {:protocol  protocol-version
     :version   (:version snapshot)
     :app       (wire/->wire (:app snapshot))
     :tools     (into [] (comp (filter ok?) (map describe-tool))
                      (sort-by :name (vals (:tools snapshot))))
     :resources (into [] (comp (filter ok?) (map describe-resource))
                      (sort-by :uri (vals (:resources snapshot))))}))
