(ns autonomous.spi
  "Extension points other packages implement to make an app agent-operable
   without editing autonomous (OCP). Each is an open multimethod keyed by a
   NAMESPACED kind, so two packages never claim the same key by accident.

   Only `derive-surface` lives here for now; the general SPI (facets,
   projections, decorators) is its own piece of work and grows this
   namespace, it does not replace it."
  (:require [autonomous.domain.answer :as answer]))

(defmulti derive-surface
  "Ok surface {:app :tools {mcp-name Tool} :resources {}} that `source` of
   kind `source-kind` proposes, or a refusal. Never throws, never writes a
   registry: the caller holds the value.

   opts: :app (AppInfo), :tools ({id spec} compiled like reg-tool, to add
   or refine). Shipped kinds (autonomous.ring.surface):
     :autonomous.ring/routes    a seq of [path route-data]
     :autonomous.reitit/router  a reitit router (reitit resolved lazily)
     :autonomous/tools          only the explicit :tools"
  (fn [source-kind _source _opts] source-kind))

(defmethod derive-surface :default [kind _ _]
  (answer/refusal :autonomous/unknown-source
                  (str "no surface derivation for source kind " (pr-str kind)
                       "; load the namespace that provides it")))
