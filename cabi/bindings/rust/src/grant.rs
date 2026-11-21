//! The caller of a tool, as libautonomous hands it to its host.

use serde_json::Value;

/// The most characters of an actor the library hands a host
/// (`AUTONOMOUS_MAX_SHOWN_ACTOR` in autonomous.h).
pub const MAX_SHOWN_ACTOR: usize = 64;

/// Who called a tool, as libautonomous shows a caller to its host: the
/// actor, never empty, with control characters dropped and at most
/// [`MAX_SHOWN_ACTOR`] characters, and the granted scopes as sorted names.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Grant {
    pub actor: String,
    pub scopes: Vec<String>,
}

impl Grant {
    /// The grant in `grant_json` (`{"actor": .., "scopes": [..]}`), or None
    /// when it names no actor.
    pub fn from_json(grant_json: &str) -> Option<Grant> {
        let v: Value = serde_json::from_str(grant_json).ok()?;
        let actor = v.get("actor")?.as_str()?.to_string();
        if actor.is_empty() {
            return None;
        }
        let scopes = v
            .get("scopes")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(|s| s.as_str().map(str::to_string))
                    .collect()
            })
            .unwrap_or_default();
        Some(Grant { actor, scopes })
    }

    /// True when the caller holds `scope`, or `admin`, which covers every scope.
    pub fn has(&self, scope: &str) -> bool {
        self.scopes.iter().any(|s| s == scope || s == "admin")
    }
}

/// What the dispatcher runs: a handler and the grant it was called with,
/// None when the library is too old to pass one.
pub(crate) type Body = Box<dyn FnMut(&Value, Option<&Grant>) -> Result<Value, String>>;

/// The body of a grant-aware tool `id`: it fails closed, refusing a call
/// that arrives without a grant before `handler` runs.
pub(crate) fn grant_body<F>(id: &str, mut handler: F) -> Body
where
    F: FnMut(&Value, &Grant) -> Result<Value, String> + 'static,
{
    let tool = id.to_string();
    Box::new(move |args, grant| {
        match grant {
        Some(g) => handler(args, g),
        None => Err(format!(
            "{tool} needs the caller's grant, which this libautonomous does not pass (0.3 or later does)"
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
    fn a_grant_body_fails_closed_without_a_grant() {
        let seen = Rc::new(Cell::new(0));
        let s = seen.clone();
        let mut body = grant_body("todo/add", move |_args, g| {
            s.set(s.get() + 1);
            Ok(json!({"by": g.actor}))
        });
        let refused = body(&json!({}), None).unwrap_err();
        assert!(refused.contains("todo/add"), "{refused}");
        assert_eq!(seen.get(), 0, "the handler did not run");
        let g = Grant {
            actor: "agent-1".into(),
            scopes: vec!["write".into()],
        };
        assert_eq!(
            body(&json!({}), Some(&g)).unwrap(),
            json!({"by": "agent-1"})
        );
        assert_eq!(seen.get(), 1);
    }
}
