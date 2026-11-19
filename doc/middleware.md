# Middleware: an app becomes agent-operable in one line

> Making an app agent-operable is like adding a logging middleware: one line
> in the composition the app already has.

`autonomous.ring` serves MCP from the app's own HTTP stack. Routes opt in
with route data, the same way reitit-swagger documents them. An agent call
runs the route's real handler chain, as a human request does, and is
attributed and recorded in the ledger.

## reitit (primary): mount a handler, opt routes in

```clojure
(require '[autonomous.ring :as autonomous])

(ring/router
  [["/mcp"   {:handler (autonomous/mcp-handler {:app {:name "todo" :description "A todo list"}})}]
   ["/todos" {:get  {:summary    "List todos"
                     :parameters {:query [:map [:done {:optional true} :boolean]]}
                     :autonomous {:expose true}
                     :handler    list-todos}
              :post {:summary    "Add a todo"
                     :parameters {:body [:map [:text [:string {:min 1}]]]}
                     :autonomous {:expose true :scope :write}
                     :handler    create-todo}}]
   ["/todos/:id" {:delete {:handler delete-todo}}]]   ; not opted in: invisible to agents
  {:data {:middleware [...]}})
```

`mcp-handler` reads `:reitit.core/router` from the request and turns the
routes that opt in into tools. It derives them once per router.

- **Tool name**: `:autonomous {:id ..}`, else the route `:name`, else
  method and path (`post_todos`).
- **Input schema**: the route's malli `:parameters` (`:path :query :form
  :body`), merged into one flat argument map. Each key is sent back to the
  location the route reads it from. If two locations declare the same key,
  derivation refuses.
- **Scopes**: `:autonomous {:scope :billing}` (or `:scopes`). The default is
  `:read` for GET and `:write` for everything else.
- **Result**: a 2xx is Ok of the body (JSON is parsed). Any other status is a
  refusal of type `:ring/http-<status>`. A route whose 2xx can still mean
  failure sets `:autonomous {:result (fn [ring-response] answer)}`.

## Plain Ring: the wrap

```clojure
(-> app
    (autonomous/wrap-autonomous {:app    {:name "todo"}
                                 :routes [["/todos" {:post {... :autonomous {:expose true}}}]]
                                 :tools  {:todo/stats {:description "Counts" :read-only? true
                                                       :handler (fn [_args _grant] (stats))}}}))
```

Requests to `:path` (default `/mcp`) are MCP. Every other request passes
through untouched. Route tools call the handler the wrap wraps, so put the
wrap outside the middleware a human request needs (body parsing, auth
adapters). `:router` takes a reitit router instead of `:routes`.

## How a call runs

A route tool is a dimap over the app's handler. Each side is a pure function
with its own tests:

```
args --(exchange/->request)--> Ring request --principal--> app handler chain
     <--(exchange/response->answer or :autonomous {:result f})-- Ring response
```

The call is planned by the same decision code as every autonomous tool:
scopes, the lease, then malli validation of the arguments. The handler never
sees a call that this plan refuses.

## Identity

The agent's grant (`{:actor :scopes}`) reaches the app through the
`:principal` port, `(fn [grant request] request)`. By default it is attached
as `:autonomous/grant`, and the app's own auth decides what it means. The
request also carries `:autonomous/call {:tool :args}`.

HTTP rules are the same as the standalone server (doc/security.md).
`:tokens` turns on bearer auth. `:grant-fn (fn [ring-request] grant-or-nil)`
lets the app's own session decide who the agent is. With neither, only
loopback callers are served, and any other caller gets 403.

## What it does not do

- It writes nothing to the process-wide registry (`reg-tool`). The surface
  is a value. It has its own journal, lease and pending results, which
  belong to the wrap or to the mounted handler. Remove the wrap or the route
  and the agent surface is gone. Two wrapped apps in one process share
  nothing.
- Nothing becomes callable just because it exists. Without
  `:autonomous {:expose true}`, a route is not listed.

## Extending

Derivation is `autonomous.spi/derive-surface`, an open multimethod keyed by
namespaced kinds: `:autonomous.ring/routes`, `:autonomous.reitit/router`,
`:autonomous/tools`. Another router or framework adds a method. Invocation
is the existing open `autonomous.runtime.invoke/invoke-kind`, and route
tools are kind `:ring/route`.

Runs on the JVM and on babashka (http-kit). reitit is optional: it is
resolved lazily, and only when a router is the source.
