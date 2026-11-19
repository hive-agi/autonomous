# OpenAPI services as agent tools

`autonomous.openapi` turns an OpenAPI 3 spec into agent tools with no
change to the service. Only the operations you name become tools.

```clojure
(require '[autonomous.openapi :as openapi]
         '[autonomous.openapi.load :as load]
         '[autonomous.openapi.invoke :as oinv])

(def spec (:ok (load/load-bundle "libs/openapi/sisf-payment.yaml"))) ; follows ./common.yaml refs

(openapi/install! spec
  {:prefix    "payment"                                   ; tools payment_getPayment, ...
   :base-url  "https://staging.example/api/payment"       ; required, never taken from `servers`
   :principal (oinv/bearer (fn [grant] (token-for (:actor grant))))
   :expose    {"getPayment"   {:scope :payment/read}
               "listPayments" {:scope :payment/read}}})
```

## How it works

| Step | Namespace | Pure? |
|------|-----------|-------|
| Read the YAML/JSON and every file its `$ref`s reach | `autonomous.openapi.load` | IO |
| Resolve `$ref`s, inside one file and across files | `autonomous.openapi.doc` | yes |
| Convert JSON Schema to malli | `autonomous.openapi.schema` | yes |
| Turn the operations you opt into into tool specs | `autonomous.openapi.derive` | yes |
| encode args into a request; decode the response into an Answer | `autonomous.openapi.codec` | yes |
| Run encode, then identity, then HTTP, then decode | `autonomous.openapi.invoke` | HTTP only |
| Default HTTP client (java.net.http, JVM and bb) | `autonomous.openapi.http` | IO |
| `derive-surface :autonomous.openapi/spec` | `autonomous.openapi.spi` | yes |

- **Input schema.** One flat map: path, query and header parameters plus the
  JSON body's properties. If a body property shares a name with a parameter,
  or the body is not an object, the whole body goes under `:body` instead.
- **decode.** A call succeeds only when the response is a 2xx that the spec
  declares and its body matches the declared schema. Everything else becomes
  a refusal that carries the server's own message (problem+json `detail`,
  then `title`, then `message`):
  - `:openapi/bad-request`, `:openapi/unauthenticated`, `:forbidden`,
    `:openapi/not-found`, `:openapi/conflict`, `:openapi/server-error`
  - `:openapi/undeclared-response`, `:openapi/response-mismatch`,
    `:openapi/unreachable`

  decode never throws.
- **Identity port.** `:principal (fn [grant request] request)`. Two helpers
  ship with it: `oinv/bearer` and `oinv/persona-headers`.
- **HTTP port.** `:http (fn [request] response)`. Pass a stub in tests or a
  gateway client in a cluster. A transport failure returns `{:error e}`
  instead of throwing.
- **Refused when you derive the tools, not when they are called:** an
  unknown operationId, an empty `:expose`, and non-JSON bodies such as
  multipart.
