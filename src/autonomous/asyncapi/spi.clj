(ns autonomous.asyncapi.spi
  "The :asyncapi/doc source kind of autonomous.spi/derive-surface.

   autonomous.spi is the extension seam: a defmethod here, and nothing else
   changes. source: an AsyncAPI Bundle or a bare document; opts: as
   autonomous.asyncapi/surface (:port is required, since deriving a surface
   opens the subscriptions)."
  (:require [autonomous.asyncapi :as asyncapi]
            [autonomous.spi :as spi]))

(def source-kind asyncapi/kind)

(defmethod spi/derive-surface source-kind
  [_ source opts]
  (asyncapi/surface source (assoc opts :prefix (or (:prefix opts) "asyncapi"))))
