//! The caller of a tool, as libautonomous hands it to its host.

use serde_json::Value;

/// The most characters of an actor the library hands a host
/// (`AUTONOMOUS_MAX_SHOWN_ACTOR` in autonomous.h). The shown actor is
/// printable ASCII, so this is also its most bytes.
pub const MAX_SHOWN_ACTOR: usize = 64;

/// Who called a tool, as libautonomous shows a caller to its host: the
/// actor, never empty, as printable ASCII with everything else escaped
/// `\uXXXX` and at most [`MAX_SHOWN_ACTOR`] characters, and the granted
/// scopes as sorted names.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Grant {
    pub actor: String,
    pub scopes: Vec<String>,
}

impl Grant {
    /// The grant in `grant_json` (`{"actor": .., "scopes": [..]}`), or None
    /// when it is not JSON or names no actor. [`GrantIn::read`] tells the two
    /// apart.
    pub fn from_json(grant_json: &str) -> Option<Grant> {
        match GrantIn::read(grant_json) {
            GrantIn::Given(g) => Some(g),
            _ => None,
        }
    }

    /// True when the caller holds `scope`, or `admin`, which covers every scope.
    pub fn has(&self, scope: &str) -> bool {
        self.scopes.iter().any(|s| s == scope || s == "admin")
    }
}

/// What reached the dispatcher about the caller.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum GrantIn {
    /// A grant naming an actor.
    Given(Grant),
    /// No grant: a libautonomous older than 0.3 called the 2-argument dispatcher.
    NotPassed,
    /// A grant was passed but is not `{"actor": "<non-empty>", ...}`; the reason.
    Unreadable(String),
}

impl GrantIn {
    pub(crate) fn read(grant_json: &str) -> GrantIn {
        let v: Value = match serde_json::from_str(grant_json) {
            Ok(v) => v,
            Err(e) => return GrantIn::Unreadable(format!("not JSON ({e})")),
        };
        let actor = match v.get("actor").and_then(Value::as_str) {
            Some(a) if !a.is_empty() => a.to_string(),
            Some(_) => return GrantIn::Unreadable("its actor is empty".into()),
            None => return GrantIn::Unreadable("it has no actor string".into()),
        };
        let scopes = v
            .get("scopes")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(|s| s.as_str().map(str::to_string))
                    .collect()
            })
            .unwrap_or_default();
        GrantIn::Given(Grant { actor, scopes })
    }
}

/// What the dispatcher runs: a handler and what it learned of the caller.
pub(crate) type Body = Box<dyn FnMut(&Value, &GrantIn) -> Result<Value, String>>;

/// The body of a grant-blind tool.
pub(crate) fn blind_body<F>(mut handler: F) -> Body
where
    F: FnMut(&Value) -> Result<Value, String> + 'static,
{
    Box::new(move |args, _| handler(args))
}

/// The body of a grant-aware tool `id`. It fails closed: a call that
/// arrives without a readable grant is refused before `handler` runs, with
/// a message that says whether no grant was passed or the grant was unreadable.
pub(crate) fn grant_body<F>(id: &str, mut handler: F) -> Body
where
    F: FnMut(&Value, &Grant) -> Result<Value, String> + 'static,
{
    let tool = id.to_string();
    Box::new(move |args, grant| {
        match grant {
        GrantIn::Given(g) => handler(args, g),
        GrantIn::NotPassed => Err(format!(
            "{tool} needs the caller's grant, which this libautonomous does not pass (0.3 or later does)"
        )),
        GrantIn::Unreadable(why) => Err(format!(
            "{tool} refused: the caller's grant is unreadable: {why}"
        )),
    }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::cell::Cell;
    use std::rc::Rc;

    #[test]
    fn grant_from_json() {
        let g = Grant::from_json(r#"{"actor":"agent-1","scopes":["read","write"]}"#).unwrap();
        assert_eq!(g.actor, "agent-1");
        assert_eq!(g.scopes, vec!["read", "write"]);
        assert!(g.has("write") && !g.has("admin"));
        assert!(Grant::from_json(r#"{"actor":"root","scopes":["admin"]}"#)
            .unwrap()
            .has("billing"));
        assert_eq!(Grant::from_json(r#"{"actor":"","scopes":["write"]}"#), None);
        assert_eq!(Grant::from_json(r#"{"scopes":["write"]}"#), None);
        assert_eq!(Grant::from_json("not json"), None);
        assert_eq!(Grant::from_json(""), None);
    }

    #[test]
    fn unreadable_is_not_missing() {
        assert!(
            matches!(GrantIn::read("not json"), GrantIn::Unreadable(w) if w.starts_with("not JSON"))
        );
        assert!(
            matches!(GrantIn::read(r#"{"actor":""}"#), GrantIn::Unreadable(w) if w.contains("empty"))
        );
        assert!(
            matches!(GrantIn::read(r#"{"actor":7}"#), GrantIn::Unreadable(w) if w.contains("no actor"))
        );
    }

    #[test]
    fn a_grant_body_fails_closed_without_a_grant() {
        let seen = Rc::new(Cell::new(0));
        let s = seen.clone();
        let mut body = grant_body("todo/add", move |_args, g| {
            s.set(s.get() + 1);
            Ok(json!({"by": g.actor}))
        });
        let missing = body(&json!({}), &GrantIn::NotPassed).unwrap_err();
        assert!(
            missing.contains("todo/add") && missing.contains("does not pass"),
            "{missing}"
        );
        let bad = body(&json!({}), &GrantIn::read("{")).unwrap_err();
        assert!(bad.contains("unreadable"), "{bad}");
        assert_eq!(seen.get(), 0, "the handler did not run");
        let g = GrantIn::read(r#"{"actor":"agent-1","scopes":["write"]}"#);
        assert_eq!(body(&json!({}), &g).unwrap(), json!({"by": "agent-1"}));
        assert_eq!(seen.get(), 1);
    }
}
