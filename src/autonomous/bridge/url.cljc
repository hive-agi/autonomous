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
   its brackets."
  [hostport]
  (if (str/starts-with? hostport "[")
    (if-let [j (str/index-of hostport "]")]
      [(subs hostport 0 (inc j)) (not-empty (subs hostport (min (count hostport) (+ j 2))))]
      [hostport nil])
    (if-let [j (str/index-of hostport ":")]
      [(subs hostport 0 j) (not-empty (subs hostport (inc j)))]
      [hostport nil])))

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
     :host      (str/lower-case host)
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

(defn loopback?
  "True when `url` names this host (localhost, 127.0.0.0/8, ::1)."
  [url]
  (let [h (:host (parts url))]
    (or (contains? loopback-hosts h)
        (some? (re-matches #"127(?:\.\d{1,3}){3}" h)))))

(defn bearer-allowed?
  "True when a bearer token may ride `url`: https anywhere, plain http only
   to this host."
  [url]
  (let [scheme (:scheme (parts url))]
    (or (= "https" scheme)
        (and (= "http" scheme) (loopback? url)))))
