package autonomous

import (
	"errors"
	"os"
	"runtime"
	"strings"
	"testing"
)

// Every test runs on the thread TestMain locked and started the runtime on:
// go test runs each test on its own goroutine, so they are handed over here.
var (
	rt    *Runtime
	tasks = make(chan func())
)

func init() { runtime.LockOSThread() }

func TestMain(m *testing.M) {
	var err error
	if rt, err = Start(); err != nil {
		panic(err)
	}
	code := make(chan int)
	go func() { code <- m.Run(); close(tasks) }()
	for {
		select {
		case f, ok := <-tasks:
			if ok {
				f()
			}
		case c := <-code:
			os.Exit(c)
		}
	}
}

// onRuntime runs f on the runtime's thread and waits for it.
func onRuntime(f func()) {
	done := make(chan struct{})
	tasks <- func() { f(); close(done) }
	<-done
}

const addSpec = `{:description "Add" :input [:map [:a :int] [:b :int]]}`

func add(by string) Handler {
	return func(args map[string]any) (any, error) {
		return map[string]any{"sum": args["a"].(float64) + args["b"].(float64), "by": by}, nil
	}
}

func TestACallDispatchesToTheHandler(t *testing.T) {
	onRuntime(func() {
		if _, err := rt.Tool("t/add", addSpec, add("go")); err != nil {
			t.Fatal(err)
		}
		got, _ := rt.RPC(`{:op :call :tool "t_add" :args {:a 2 :b "40"} :scopes #{:write}}`)
		if !strings.Contains(got, ":sum 42") {
			t.Fatal(got)
		}
	})
}

func TestEveryCallOffTheThreadIsRefused(t *testing.T) {
	// This goroutine is not the runtime's; lock it so the refusal is not luck.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	checks := map[string]error{"Describe": rt.Describe("x", "y")}
	_, checks["Tool"] = rt.Tool("t/off", addSpec, add("off"))
	_, checks["RPC"] = rt.RPC(`{:op :ping}`)
	_, checks["Journal"] = rt.Journal(map[string]any{"kind": "x"})
	_, checks["WsHello"] = rt.WsHello("t", "r", "l")
	_, checks["WsKind"] = rt.WsKind("{}")
	_, checks["WsReply"] = rt.WsReply("{}")
	for name, err := range checks {
		if !errors.Is(err, ErrWrongThread) {
			t.Errorf("%s off the runtime's thread: %v", name, err)
		}
	}
	if _, ok := handlers["t/off"]; ok {
		t.Error("a refused Tool call registered its handler")
	}
}

func TestAnUnlockedGoroutineNeverEntersTheLibrary(t *testing.T) {
	// No LockOSThread: whatever thread each call lands on, it either is the
	// runtime's (impossible here: that thread is busy in TestMain's loop) or
	// is refused; none may reach jolt.
	for i := 0; i < 200; i++ {
		if _, err := rt.RPC(`{:op :ping}`); !errors.Is(err, ErrWrongThread) {
			t.Fatalf("call %d: %v", i, err)
		}
		runtime.Gosched()
	}
}
