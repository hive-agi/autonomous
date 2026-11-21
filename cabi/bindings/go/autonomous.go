// Package autonomous binds libautonomous for Go apps: declare tools an
// agent may call, and let the library validate, scope and journal every call.
//
//	runtime.LockOSThread() // in main's init: every call stays on one OS thread
//	rt, err := autonomous.Start()
//	rt.Describe("calc", "A Go calculator. calc_add adds two integers.")
//	name, err := rt.Tool("calc/add",
//	    `{:description "Add two integers" :input [:map [:a :int] [:b :int]]}`,
//	    func(args map[string]any) (any, error) { ... })
//	answer, err := rt.RPC(`{:op :call :tool "calc_add" :args {:a 2 :b 40} :scopes #{:write}}`)
//
// The jolt runtime inside the library is single-threaded: Start and every
// method must run on the OS thread that called Start, and the library calls
// tool handlers back on that thread. Go moves goroutines between threads,
// so the goroutine using a Runtime must hold runtime.LockOSThread for good;
// the simplest way is to lock main's thread in init (see example/README.md).
// A call from any other thread is refused with ErrWrongThread and never
// reaches the library.
//
// Build: libautonomous.so in ../../build (bb cabi), or set CGO_LDFLAGS to
// -L<dir> -Wl,-rpath,<dir>. The executable exports autonomous_host_dispatch
// (-rdynamic, set below) so the library can find it.
package autonomous

/*
#cgo CFLAGS: -I${SRCDIR}/../../include
#cgo LDFLAGS: -L${SRCDIR}/../../build -Wl,-rpath,${SRCDIR}/../../build -lautonomous -rdynamic
#include <stdlib.h>
#include <unistd.h>
#include <sys/syscall.h>
#include "autonomous.h"

static long autonomous_go_tid(void) { return (long)syscall(SYS_gettid); }

// Each shim compares the calling thread with the runtime's and calls the
// library only on a match, in one cgo call; *wrong is set otherwise.
static int call_i1(long tid, int *wrong, void *f, const char *a) {
  if (autonomous_go_tid() != tid) { *wrong = 1; return 0; }
  return ((autonomous_set_app_fn)f)(a);
}
static long call_l1(long tid, int *wrong, void *f, const char *a) {
  if (autonomous_go_tid() != tid) { *wrong = 1; return 0; }
  return ((autonomous_journal_fn)f)(a);
}
static const char *call_s1(long tid, int *wrong, void *f, const char *a) {
  if (autonomous_go_tid() != tid) { *wrong = 1; return NULL; }
  return ((autonomous_rpc_fn)f)(a);
}
static const char *call_s2(long tid, int *wrong, void *f, const char *a, const char *b) {
  if (autonomous_go_tid() != tid) { *wrong = 1; return NULL; }
  return ((autonomous_reg_tool_fn)f)(a, b);
}
static const char *call_s3(long tid, int *wrong, void *f, const char *a, const char *b, const char *c) {
  if (autonomous_go_tid() != tid) { *wrong = 1; return NULL; }
  return ((autonomous_ws_hello_fn)f)(a, b, c);
}
*/
import "C"

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"unsafe"
)

// Handler runs one tool: validated JSON arguments in, a JSON-encodable
// result or an error out. The error's text is what the agent reads.
type Handler func(args map[string]any) (any, error)

// ErrWrongThread is returned by a call made off the thread that started
// the runtime; the library is not entered.
var ErrWrongThread = errors.New("autonomous: called off the runtime's OS thread (hold runtime.LockOSThread)")

// Runtime is the process's one libautonomous runtime.
type Runtime struct {
	tid                                 C.long
	setApp, regTool, unregTool, journal unsafe.Pointer
	rpc, wsHello, wsKind, wsReply       unsafe.Pointer
}

var (
	startOnce sync.Once
	started   *Runtime
	startErr  error

	handlers = map[string]Handler{}
)

func cs(s string) *C.char { return C.CString(s) }

func free(p *C.char) { C.free(unsafe.Pointer(p)) }

func text(p *C.char) string {
	if p == nil {
		return ""
	}
	return C.GoString(p)
}

func lookup(name string) (unsafe.Pointer, error) {
	n := cs(name)
	defer free(n)
	p := C.jolt_lookup(n)
	if p == nil {
		return nil, fmt.Errorf("libautonomous has no export %s", name)
	}
	return p, nil
}

// Start starts the runtime once per process, on the calling OS thread,
// which the caller must have locked with runtime.LockOSThread.
func Start() (*Runtime, error) {
	startOnce.Do(func() {
		if C.jolt_library_init(0, nil) != 0 {
			startErr = errors.New("jolt_library_init failed")
			return
		}
		rt := &Runtime{tid: C.autonomous_go_tid()}
		for _, e := range []struct {
			dst  *unsafe.Pointer
			name string
		}{
			{&rt.setApp, "autonomous_set_app"}, {&rt.regTool, "autonomous_reg_tool"},
			{&rt.unregTool, "autonomous_unreg_tool"}, {&rt.journal, "autonomous_journal"},
			{&rt.rpc, "autonomous_rpc"}, {&rt.wsHello, "autonomous_ws_hello"},
			{&rt.wsKind, "autonomous_ws_kind"}, {&rt.wsReply, "autonomous_ws_reply"},
		} {
			p, err := lookup(e.name)
			if err != nil {
				startErr = err
				return
			}
			*e.dst = p
		}
		started = rt
	})
	return started, startErr
}

func (rt *Runtime) s1(f unsafe.Pointer, a string) (string, error) {
	ca := cs(a)
	defer free(ca)
	var wrong C.int
	out := C.call_s1(rt.tid, &wrong, f, ca)
	if wrong != 0 {
		return "", ErrWrongThread
	}
	return text(out), nil
}

func (rt *Runtime) i1(f unsafe.Pointer, a string) (int, error) {
	ca := cs(a)
	defer free(ca)
	var wrong C.int
	out := C.call_i1(rt.tid, &wrong, f, ca)
	if wrong != 0 {
		return 0, ErrWrongThread
	}
	return int(out), nil
}

// Describe names and describes the app; the description is what an agent
// reads first.
func (rt *Runtime) Describe(name, description string) error {
	info, _ := json.Marshal(map[string]string{"name": name, "description": description})
	_, err := rt.i1(rt.setApp, string(info))
	return err
}

// Tool declares tool id ("calc/add", MCP name calc_add) from an EDN spec
// with a malli :input (bb tool-spec turns a JSON spec into one) and runs it
// with h. Answers the MCP name.
func (rt *Runtime) Tool(id, specEDN string, h Handler) (string, error) {
	cid, cspec := cs(id), cs(specEDN)
	defer free(cid)
	defer free(cspec)
	handlers[id] = h
	var wrong C.int
	out := text(C.call_s2(rt.tid, &wrong, rt.regTool, cid, cspec))
	if wrong != 0 {
		delete(handlers, id)
		return "", ErrWrongThread
	}
	var a struct {
		Ok string `json:"ok"`
	}
	if json.Unmarshal([]byte(out), &a) != nil || a.Ok == "" {
		delete(handlers, id)
		return "", fmt.Errorf("autonomous: tool %s refused: %s", id, out)
	}
	return a.Ok, nil
}

// Journal records an app event agents can read, e.g.
// map[string]any{"kind": "calc/cleared", "by": "human"}. Answers its
// sequence number.
func (rt *Runtime) Journal(entry map[string]any) (int64, error) {
	b, err := json.Marshal(entry)
	if err != nil {
		return 0, err
	}
	ce := cs(string(b))
	defer free(ce)
	var wrong C.int
	seq := C.call_l1(rt.tid, &wrong, rt.journal, ce)
	if wrong != 0 {
		return 0, ErrWrongThread
	}
	return int64(seq), nil
}

// RPC answers one EDN request in process (tests, local tooling) with EDN
// Answer text.
func (rt *Runtime) RPC(requestEDN string) (string, error) { return rt.s1(rt.rpc, requestEDN) }

// WsHello is the first frame to send on a new websocket to the bridge.
func (rt *Runtime) WsHello(token, runtimeID, label string) (string, error) {
	a, b, c := cs(token), cs(runtimeID), cs(label)
	defer free(a)
	defer free(b)
	defer free(c)
	var wrong C.int
	out := C.call_s3(rt.tid, &wrong, rt.wsHello, a, b, c)
	if wrong != 0 {
		return "", ErrWrongThread
	}
	return text(out), nil
}

// WsKind says what a bridge frame asks: "request", "welcome", "refused",
// "fatal" or "other".
func (rt *Runtime) WsKind(frame string) (string, error) { return rt.s1(rt.wsKind, frame) }

// WsReply is the frame answering a bridge frame, "" when none is due. It
// runs the tool, and so its handler, before it returns.
func (rt *Runtime) WsReply(frame string) (string, error) { return rt.s1(rt.wsReply, frame) }
