// A Go app made agent-operable with libautonomous: it registers one tool,
// dispatches a call through the library (validation, scopes, journal) and
// prints the answer. It exits 1 when an answer is not the expected one, so
// `bb cabi-go` gates the binding.
//
//	go run .            (with cabi/build/libautonomous.so built by bb cabi)
package main

import (
	"fmt"
	"os"
	"runtime"
	"strings"

	autonomous "github.com/hive-agi/autonomous/cabi/bindings/go"
)

// libautonomous runs on one jolt thread: lock main's goroutine to the OS
// thread it starts on, before main runs, and make every call from main.
func init() { runtime.LockOSThread() }

func main() {
	rt, err := autonomous.Start()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	rt.Describe("calc", "A Go calculator. calc_add adds two integers.")

	name, err := rt.Tool("calc/add",
		`{:description "Add two integers (in Go)" :input [:map [:a :int] [:b :int]]}`,
		func(args map[string]any) (any, error) {
			a, _ := args["a"].(float64)
			b, _ := args["b"].(float64)
			return map[string]any{"sum": int64(a + b), "by": "go"}, nil
		})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println("registered", name)

	failures := 0
	check := func(what, request, want string) {
		got, err := rt.RPC(request)
		ok := err == nil && strings.Contains(got, want)
		mark := "ok  "
		if !ok {
			mark, failures = "FAIL", failures+1
		}
		fmt.Printf("%s %-8s %s\n", mark, what, got)
	}

	// "40" is decoded to 40 by the library before the handler sees it.
	check("call", `{:op :call :tool "calc_add" :args {:a 2 :b "40"} :scopes #{:write} :actor "agent-1"}`, ":sum 42")
	check("invalid", `{:op :call :tool "calc_add" :args {:a "x"} :scopes #{:write}}`, ":invalid-args")
	check("scope", `{:op :call :tool "calc_add" :args {:a 1 :b 2}}`, ":forbidden")

	// Off the runtime's thread a call is refused, not run.
	done := make(chan error)
	go func() {
		runtime.LockOSThread()
		_, err := rt.RPC(`{:op :ping}`)
		done <- err
	}()
	if err := <-done; err == autonomous.ErrWrongThread {
		fmt.Println("ok   thread   another OS thread is refused")
	} else {
		fmt.Println("FAIL thread  ", err)
		failures++
	}

	if failures > 0 {
		fmt.Printf("\n%d FAILED\n", failures)
		os.Exit(1)
	}
	fmt.Println("\nall ok")
}
