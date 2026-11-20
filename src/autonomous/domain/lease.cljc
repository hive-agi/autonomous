(ns autonomous.domain.lease
  "Lease rules. A swarm shares runtimes; a lease lets one actor write without
   others interleaving, while every actor keeps reading. Time is a parameter,
   never read here. Pure.")

(def default-ttl-ms 60000)

(def max-ttl-ms
  "The longest lease anyone gets, whatever they ask for: a lease blocks every
   other actor's writes, so an unbounded one would let one agent lock a
   runtime for good."
  300000)

(defn ttl
  "The lease length granted for a request of `ttl-ms` (nil: the default),
   between 1 ms and `max-ttl-ms`."
  [ttl-ms]
  (-> (or ttl-ms default-ttl-ms) (max 1) (min max-ttl-ms)))

(defn live?
  "True when `lease` exists and has not ended at `now`."
  [lease now]
  (boolean (and lease (> (:until lease) now))))

(defn blocks?
  "True when a live lease held by ANOTHER actor forbids `grant` from running
   `tool`. Read-only tools are never blocked."
  [lease tool grant now]
  (and (live? lease now)
       (not= (:actor lease) (:actor grant))
       (not (:read-only? tool))))

(defn acquire
  "Decide `actor`'s request for a lease of `ttl-ms` at `now`.
   Answers [:granted lease'] (a fresh lease, or a renewal of its own) or
   [:refused lease] while another actor's lease is live."
  [lease actor ttl-ms now]
  (if (and (live? lease now) (not= actor (:actor lease)))
    [:refused lease]
    [:granted {:actor actor :until (+ now (ttl ttl-ms))}]))

(defn release
  "The lease after `actor` releases it: nil when `actor` held it, else
   unchanged (releasing what you do not hold changes nothing)."
  [lease actor]
  (if (= actor (:actor lease)) nil lease))
