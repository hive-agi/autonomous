(ns autonomous.bridge.failure
  "Pure stratum: the text a transport failure may show. An exception's own
   message can quote a header, a url or a remote's reply, so a refusal
   never carries it. It names the failure by an allowlist of exception
   classes, each with a fixed message, and otherwise by the class alone.")

(def ^:private safe-messages
  "Fixed messages by exception class name, the classes a transport throws."
  {"java.net.ConnectException"                 "the connection was refused"
   "java.net.SocketTimeoutException"           "the connection timed out"
   "java.net.http.HttpTimeoutException"        "the request timed out"
   "java.net.http.HttpConnectTimeoutException" "the connection timed out"
   "java.net.UnknownHostException"             "the host is unknown"
   "java.net.SocketException"                  "the connection broke"
   "java.net.NoRouteToHostException"           "there is no route to the host"
   "java.nio.channels.ClosedChannelException"  "the connection was closed"
   "java.io.EOFException"                      "the connection ended early"
   "javax.net.ssl.SSLHandshakeException"       "the TLS handshake failed"
   "java.lang.IllegalArgumentException"        "the request could not be built"
   "java.lang.InterruptedException"            "the call was interrupted"})

(def ^:private before-request-classes
  "Exception classes that are thrown before a request reaches the remote:
   it cannot have acted, so a retry is safe. Any other failure may come
   after the request was written."
  #{"java.net.ConnectException"
    "java.net.http.HttpConnectTimeoutException"
    "java.net.UnknownHostException"
    "java.net.NoRouteToHostException"
    "javax.net.ssl.SSLHandshakeException"
    "java.lang.IllegalArgumentException"})

(defn before-request?
  "Pure: true when exception `e` is known to precede the request reaching
   the remote (connect, resolve, route, handshake, or building it)."
  [^Throwable e]
  (contains? before-request-classes (.getName (class e))))

(defn message
  "Pure: the text a refusal of exception `e` may carry: the fixed message
   of its class from the allowlist, else only its class name. Never the
   exception's own message."
  [^Throwable e]
  (let [cls (.getName (class e))]
    (or (get safe-messages cls) (str "the transport failed (" cls ")"))))
