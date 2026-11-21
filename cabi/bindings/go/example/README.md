# Go example: one tool over libautonomous

`main.go` starts the runtime, registers `calc/add` (MCP name `calc_add`),
dispatches three calls through the library and prints each answer:

```
registered calc_add
ok   call     {:ok {:by "go", :sum 42}}
ok   invalid  {:error {:type :invalid-args, ...}}
ok   scope    {:error {:type :forbidden, ...}}
ok   thread   another OS thread is refused
```

The library decodes and validates the arguments (`"40"` arrives as `40`),
checks scopes and journals the call before the Go handler runs.

## Build and run

```sh
bb cabi       # cabi/build/libautonomous.so (needs jolt and JOLT_CHEZ_CSV)
bb cabi-go    # go vet, go build, then run this example; fails on a wrong answer
```

or by hand, from this directory: `go run .`. The binding's cgo flags link
`../../../build/libautonomous.so` with an rpath to it and `-rdynamic`, so the
library can find `autonomous_host_dispatch` in the executable. To link a
library somewhere else, set `CGO_LDFLAGS="-L<dir> -Wl,-rpath,<dir>"`.

## Why `runtime.LockOSThread`

libautonomous embeds the jolt runtime, and jolt runs on **one OS thread**:
the thread that called `jolt_library_init`. Every call into the library has
to come from that thread, and the library calls the tool handlers back on
it too.

The Go scheduler moves goroutines between OS threads whenever it likes, so
a goroutine that calls `autonomous.Start` can resume on a different thread
a moment later and enter jolt from the wrong one. `runtime.LockOSThread`
pins the goroutine to its thread. The example locks the main goroutine in
`init`, which runs on the process's first thread before `main`:

```go
func init() { runtime.LockOSThread() }
```

and makes every `Runtime` call from `main`. Keep it locked for the life of
the program: never call `runtime.UnlockOSThread` on that goroutine.

Other goroutines must not call the `Runtime`. Each call checks the calling
thread in the same cgo call that enters the library and answers
`autonomous.ErrWrongThread` off it, so a call from the wrong thread never
reaches jolt; the example shows that refusal. `rt.Unregister(id)` removes a
tool, and a refused re-registration leaves the working one in place.
`go test` in `cabi/bindings/go` covers the binding (bb cabi-go runs it). Hand work to the runtime goroutine over a channel instead,
and do slow work in a handler on your own goroutines, answering from what
they leave behind, because a handler blocks the runtime while it runs.
