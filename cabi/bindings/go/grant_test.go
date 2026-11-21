package autonomous

import (
	"strings"
	"testing"
)

const whoSpec = `{:description "Who called" :input [:map] :read-only? true}`

func TestAToolAsHandlerIsToldWhoCalled(t *testing.T) {
	onRuntime(func() {
		if _, err := rt.ToolAs("t/who", whoSpec, func(_ map[string]any, who Grant) (any, error) {
			return map[string]any{"actor": who.Actor, "scopes": who.Scopes, "write": who.Has("write")}, nil
		}); err != nil {
			t.Fatal(err)
		}
		got, _ := rt.RPC(`{:op :call :tool "t_who" :actor "agent-1" :scopes #{:write :read}}`)
		for _, want := range []string{`:actor "agent-1"`, `:scopes ["read" "write"]`, `:write true`} {
			if !strings.Contains(got, want) {
				t.Fatalf("want %s in %s", want, got)
			}
		}
		got, _ = rt.RPC(`{:op :call :tool "t_who" :actor "agent:\u202Enamuh" :scopes #{:read}}`)
		if !strings.Contains(got, `:actor "agent:\\u202enamuh"`) {
			t.Fatal("a bidi actor arrives escaped, got", got)
		}
	})
}

func TestTheLibraryRefusesANamelessOrTooLongActorBeforeTheHandler(t *testing.T) {
	onRuntime(func() {
		ran := 0
		if _, err := rt.ToolAs("t/guarded", whoSpec, func(map[string]any, Grant) (any, error) {
			ran++
			return map[string]any{}, nil
		}); err != nil {
			t.Fatal(err)
		}
		for req, want := range map[string]string{
			`{:op :call :tool "t_guarded" :actor " \t"}`:                             ":host/grant-missing",
			`{:op :call :tool "t_guarded" :actor "` + strings.Repeat("x", 65) + `"}`: ":host/actor-too-long",
		} {
			if got, _ := rt.RPC(req); !strings.Contains(got, want) {
				t.Errorf("%s: want %s, got %s", req, want, got)
			}
		}
		if ran != 0 {
			t.Fatal("the handler ran", ran, "times")
		}
	})
}

func TestAGrantBlindToolStillWorks(t *testing.T) {
	onRuntime(func() {
		if _, err := rt.Tool("t/blind", addSpec, add("blind")); err != nil {
			t.Fatal(err)
		}
		got, _ := rt.RPC(`{:op :call :tool "t_blind" :args {:a 1 :b 2} :actor "agent-1" :scopes #{:write}}`)
		if !strings.Contains(got, `:by "blind"`) {
			t.Fatal(got)
		}
	})
}

func TestAGrantBodyFailsClosedWithoutAGrant(t *testing.T) {
	ran := 0
	b := grantBody("t/x", func(map[string]any, Grant) (any, error) { ran++; return nil, nil })
	if _, err := b(nil, grantIn{}); err == nil || !strings.Contains(err.Error(), "does not pass") {
		t.Fatal("a 0.2 call (no grant) must be refused:", err)
	}
	if _, err := b(nil, readGrant("{oops")); err == nil || !strings.Contains(err.Error(), "unreadable") {
		t.Fatal("an unreadable grant must be refused:", err)
	}
	if _, err := b(nil, readGrant(`{"actor":"","scopes":["admin"]}`)); err == nil {
		t.Fatal("an empty actor must be refused")
	}
	if ran != 0 {
		t.Fatal("the handler ran")
	}
	if _, err := b(nil, readGrant(`{"actor":"a","scopes":[]}`)); err != nil || ran != 1 {
		t.Fatal("a readable grant must run the handler:", err, ran)
	}
}

func TestGrantHas(t *testing.T) {
	if !(Grant{Scopes: []string{"admin"}}).Has("billing") || (Grant{Scopes: []string{"read"}}).Has("write") {
		t.Fatal("Has")
	}
}
