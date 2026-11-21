package autonomous

import (
	"encoding/json"
	"fmt"
)

// MaxShownActor is the most characters of an actor the library hands a
// host (AUTONOMOUS_MAX_SHOWN_ACTOR); longer actors are refused, not cut.
const MaxShownActor = 64

// Grant is who called a tool, as libautonomous 0.3 shows the caller to its
// host: the actor, never empty, as printable ASCII with everything else
// escaped \uxxxx, and the granted scopes as sorted names.
type Grant struct {
	Actor  string   `json:"actor"`
	Scopes []string `json:"scopes"`
}

// Has reports whether the caller holds scope, or admin, which covers every scope.
func (g Grant) Has(scope string) bool {
	for _, s := range g.Scopes {
		if s == scope || s == "admin" {
			return true
		}
	}
	return false
}

// GrantHandler runs one tool and is told who called it.
type GrantHandler func(args map[string]any, who Grant) (any, error)

// grantIn is what reached the dispatcher about the caller.
type grantIn struct {
	grant  *Grant // set when a readable grant was passed
	passed bool   // false: a 0.2 library called the grant-blind dispatcher
	why    string // why a passed grant is unreadable
}

func readGrant(grantJSON string) grantIn {
	var g Grant
	if err := json.Unmarshal([]byte(grantJSON), &g); err != nil {
		return grantIn{passed: true, why: "not JSON (" + err.Error() + ")"}
	}
	if g.Actor == "" {
		return grantIn{passed: true, why: "it names no actor"}
	}
	return grantIn{grant: &g, passed: true}
}

// body is what the dispatcher runs for one registered tool.
type body func(args map[string]any, in grantIn) (any, error)

func blindBody(h Handler) body {
	return func(args map[string]any, _ grantIn) (any, error) { return h(args) }
}

// grantBody fails closed: a call that reaches tool id without a readable
// grant is refused before h runs.
func grantBody(id string, h GrantHandler) body {
	return func(args map[string]any, in grantIn) (any, error) {
		switch {
		case in.grant != nil:
			return h(args, *in.grant)
		case !in.passed:
			return nil, fmt.Errorf("%s needs the caller's grant, which this libautonomous does not pass (0.3 or later does)", id)
		default:
			return nil, fmt.Errorf("%s refused: the caller's grant is unreadable: %s", id, in.why)
		}
	}
}
