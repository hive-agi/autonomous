(ns autonomous.bridge.url
  "Pure reading of a remote app's URL: the part a label, a manifest or a
   log may show, and whether a bearer token may ride it. Plain string work,
   so the JVM, babashka and ClojureWasm share it.

   A URL never carries a credential here: userinfo, query and fragment are
   dropped from everything shown, and configuration refuses them
   (autonomous.bridge.schema/HttpTarget)."
  (:require [clojure.string :as str]))

(def ^:private url-re
  "scheme, authority and path of a URL; query and fragment are not captured."
  #"^(?:([A-Za-z][A-Za-z0-9+.\-]*)://)?([^/?#]*)([^?#]*)")

(defn- host-port
  "[host port] of authority `hostport` (no userinfo); an IPv6 host keeps
   its brackets. An authority that is not a host and an optional
   :port - a bracketed host followed by anything but :port, a port that
   is not digits - answers [nil nil], so nothing reads it as a host."
  [hostport]
  (if-let [[_ host port] (or (re-matches #"(\[[^\]]*\])(?::([0-9]*))?" hostport)
                             (re-matches #"([^\[\]:]*)(?::([0-9]*))?" hostport))]
    [host (not-empty port)]
    [nil nil]))

(defn parts
  "{:scheme :userinfo? :host :port :path} of `url`, scheme and host lower
   case. Query and fragment are not part of the answer."
  [url]
  (let [[_ scheme authority path] (re-find url-re (str url))
        authority   (str authority)
        at          (str/last-index-of authority "@")
        [host port] (host-port (if at (subs authority (inc at)) authority))]
    {:scheme    (some-> scheme str/lower-case)
     :userinfo? (some? at)
     :host      (some-> host str/lower-case)
     :port      port
     :path      (str path)}))

(defn redacted
  "`url` without userinfo, query or fragment: the only form of a remote
   URL that a label, a manifest or a request line carries."
  [url]
  (let [{:keys [scheme host port path]} (parts url)]
    (str (when scheme (str scheme "://")) host (when port (str ":" port)) path)))

(defn clean?
  "True when `url` carries no userinfo, query or fragment."
  [url]
  (and (string? url)
       (not (:userinfo? (parts url)))
       (not (re-find #"[?#]" url))))

(def loopback-hosts #{"localhost" "::1" "[::1]"})

(def ^:private octet-re
  "One decimal IPv4 octet, 0-255, without a leading zero."
  "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)")

(def ^:private loopback-v4-re
  (re-pattern (str "127(?:\\." octet-re "){3}")))

(defn loopback?
  "True when `url` names this host (localhost, 127.0.0.0/8, ::1). A 127.x
   host with an octet past 255 is no address, so it is a name a resolver
   may send anywhere: not loopback."
  [url]
  (let [h (:host (parts url))]
    (and (some? h)
         (or (contains? loopback-hosts h)
             (some? (re-matches loopback-v4-re h))))))

(defn bearer-allowed?
  "True when a bearer token may ride `url`: https anywhere, plain http only
   to this host."
  [url]
  (let [scheme (:scheme (parts url))]
    (or (= "https" scheme)
        (and (= "http" scheme) (loopback? url)))))
