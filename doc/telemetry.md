# Telemetry

autonomous records what happens in an app as OpenTelemetry spans. That
covers what a person or an agent did, what the app did about it, and what
came back:

- clicks, submits, changes, Enter/Escape and navigation;
- every re-frame event, with the app-db paths it changed, the effects it
  asked for and the events it dispatched;
- fetch and XMLHttpRequest calls, and the server span that answered each
  one;
- agent tool calls, refused calls and results that settle later;
- uncaught errors, unhandled rejections and `console.error`.

Every span has a cause. A click is the parent of the event it dispatched,
which is the parent of the request that event sent. The server span joins
the same trace through the W3C `traceparent` header. At the end of an
agentic or automated test run, the trace shows what went wrong and the
chain that led there.

Tracing is off by default and costs nothing until it is turned on.

## The model

The span model follows OpenTelemetry, so any OTel backend reads it as it
is:

| field | meaning |
|---|---|
| `:trace-id` / `:span-id` / `:parent-id` | lowercase hex, 32 and 16 digits (W3C Trace Context) |
| `:kind` | SpanKind: `:internal` `:server` `:client` `:producer` `:consumer` |
| `:type` | what recorded it: `:ui/click`, `:re-frame/event`, `:http/client`, `:http/server`, `:agent/call`, `:error/uncaught`, `:test/mark`, ... |
| `:start` / `:end` | epoch milliseconds, fractional in the browser |
| `:status` | `:ok` or `:error`, with `:error {:message :class :stack}` |
| `:attrs` | semantic-convention keys: `:http.request.method`, `:url.full`, `:http.response.status_code`, `:http.route`, `:error.type`, ... |
| `:actor` | the agent or person who caused it, when known |

Attributes autonomous adds itself sit under their own prefixes:
`re-frame.event.id`, `re-frame.db.changed`, `re-frame.effects`,
`re-frame.dispatched`, `autonomous.tool`, `autonomous.outcome` and
`ui.element.*`. The whole list is `autonomous.domain.trace/semconv`.

Values typed into fields are never recorded, only their length, and
password fields not even that. Event arguments and request bodies are
recorded only with `:payloads? true`.

## Turn it on

### In a re-frame app

```clojure
(autonomous.re-frame/install-tracing! {:payloads? dev?})
```

This installs the browser capture (DOM, fetch, XHR, errors) and the
re-frame capture together: a global interceptor, dispatch tagging and the
event error handler. Each half also works on its own:
`autonomous.trace.browser/install!` traces any page, and
`autonomous.re-frame.trace/install!` traces re-frame alone. The example
todo app turns tracing on in `example/src/todo/agentic.cljs`.

### In a JavaScript app

```js
autonomous.startTracing({payloads: false});
autonomous.traceMark('test: add then remove');
const spans = autonomous.traceSpans();      // plain objects
const json  = autonomous.traceJson();       // for page.evaluate in Playwright
```

### On a Ring server

```clojure
(-> handler
    (autonomous.ring.trace/wrap-trace {:route-of (fn [req] (:template (reitit.core/match-by-path ...)))}))
```

A request that carries `traceparent` continues the browser's trace: its
`:http/server` span is a child of the browser's `:http/client` span. Spans
the handler records with `autonomous.runtime.trace/in-span` are children
of the server span. Both sync and async (3-arity) Ring are supported. A 5xx
or a throw ends the span as an error. Outgoing calls carry the context
with `(autonomous.runtime.trace/headers)`.

### From an agent

`autonomous_trace_config {enabled: true}` turns recording on in the
attached instance. It needs the `:admin` scope.

## Read it

The bridge collects spans from every runtime of every app. It reads each
runtime from where it stopped. A reloaded page starts a new epoch and is
read again from the start. The bridge keeps up to 100 000 spans.

| tool | what it answers |
|---|---|
| `autonomous_trace` | `view` = `traces` (one summary per trace, the default), `trace` (one trace as an outline and a tree; `trace_id`, a prefix is enough), `timeline` (every span through time), `findings` (errors, failed requests, slow spans and errors with no cause, each with its chain of causes) or `metrics` (count, errors, error rate, p50/p95 per span name). Filter with `target`, `since`, `limit` and `slow_ms` |
| `autonomous_trace_mark` | a root `:test/mark` span: "test: checkout happy path" |
| `autonomous_trace_export` | send the collected spans to the configured exporters |
| `autonomous_trace_config` | on or off, payloads, ring size (admin) |

A findings row looks like this:

```clojure
{:finding :http-server-error
 :path    ["click Save" ":todo/save" "POST /api/todos" "POST /api/todos"]
 :span    {...}}
```

It reads as: the click dispatched `:todo/save`, which sent the request, and
the server answered it with a 500.

## Export it

Exporters are providers behind one port, `ISpanExporter`, with a single
method. Each provider answers an Answer and never throws, so they can stand
in for one another. Configure any number of them:

```sh
autonomous --trace-otlp http://localhost:4318/v1/traces   # an OpenTelemetry collector, Jaeger, Tempo
autonomous --trace-dir target/traces --trace-format jsonl # otlp-json (default), jsonl, edn
autonomous --trace-poll-ms 2000                           # collect in the background, not only on read
```

You can also set exporters in `autonomous.edn`:

```clojure
{:trace {:exporters [{:exporter/kind :otlp-http
                      :otlp/endpoint "http://localhost:4318/v1/traces"
                      :otlp/headers  {"authorization" "Bearer ..."}}
                     {:exporter/kind :file :file/dir "target/traces" :file/format :otlp-json}]
         :poll-ms 2000}}
```

To add a provider, add a method:
`(defmethod autonomous.bridge.trace.export/make :my-kind [cfg] ...)`
returning an `ISpanExporter`. An exporter that cannot start is reported in
the collector's status and skipped; it does not stop the bridge.

The OTLP body is an OTLP/JSON `ExportTraceServiceRequest`, with one
resource per app and `service.name` set to the target. Timestamps are
nanosecond strings at microsecond precision.

## Where it lives

| namespace | stratum |
|---|---|
| `autonomous.domain.trace` | pure: the span schema, traceparent, tree, timeline, outline, findings, metrics, OTLP |
| `autonomous.runtime.trace` | the current context, start/finish, the bounded ring in the registry |
| `autonomous.re-frame.model` | pure: what an event changed and dispatched, tagging causes |
| `autonomous.re-frame.trace`, `autonomous.trace.browser`, `autonomous.trace.dom` | page capture |
| `autonomous.ring.trace` | server spans |
| `autonomous.bridge.trace` | the collector and its views |
| `autonomous.bridge.trace.{export,otlp,file}` | the exporter port and its providers |
