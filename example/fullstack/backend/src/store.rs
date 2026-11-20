//! The todo store and its limits. One list, shared by humans and agents.

use autonomous::{json, Value};

/// How much one store holds. A public playground sets all of them.
#[derive(Clone, Copy)]
pub struct Limits {
    /// Most todos at once; 0 is no limit.
    pub max_todos: usize,
    /// Longest todo, in characters.
    pub max_text: usize,
    /// Most open event streams (browser tabs).
    pub max_streams: usize,
}

pub struct Store {
    todos: Vec<(i64, String, bool)>,
    next: i64,
    pub limits: Limits,
}

/// What a fresh (or freshly reset) list holds.
const SEED: [&str; 3] = [
    "Connect your agent (see the panel)",
    "Ask it to add a todo, then watch it appear here",
    "Tick one yourself, and ask the agent what you did",
];

impl Store {
    pub fn new(limits: Limits) -> Store {
        Store {
            todos: Vec::new(),
            next: 0,
            limits,
        }
    }

    pub fn json(&self) -> Value {
        Value::Array(
            self.todos
                .iter()
                .map(|(id, text, done)| json!({"id": id, "text": text, "done": done}))
                .collect(),
        )
    }

    /// Add a todo, unless it breaks a limit.
    pub fn add(&mut self, text: &str) -> Result<Value, String> {
        let l = self.limits;
        if text.is_empty() {
            return Err("text is required".into());
        }
        if text.chars().count() > l.max_text {
            return Err(format!("text is longer than {} characters", l.max_text));
        }
        if l.max_todos > 0 && self.todos.len() >= l.max_todos {
            return Err(format!(
                "the list is full ({} todos): remove or clear some first",
                l.max_todos
            ));
        }
        self.next += 1;
        self.todos.push((self.next, text.to_string(), false));
        Ok(json!({"id": self.next, "text": text, "done": false}))
    }

    pub fn toggle(&mut self, id: i64) -> Option<Value> {
        let t = self.todos.iter_mut().find(|t| t.0 == id)?;
        t.2 = !t.2;
        Some(json!({"id": t.0, "text": t.1, "done": t.2}))
    }

    pub fn remove(&mut self, id: i64) -> Option<String> {
        let i = self.todos.iter().position(|t| t.0 == id)?;
        Some(self.todos.remove(i).1)
    }

    pub fn clear_done(&mut self) -> usize {
        let before = self.todos.len();
        self.todos.retain(|t| !t.2);
        before - self.todos.len()
    }

    /// Empty the list and seed it again.
    pub fn reset(&mut self) {
        self.todos.clear();
        for text in SEED {
            self.add(text).ok();
        }
    }
}

/// How an add reads in the event stream and the journal.
pub fn added(r: &Result<Value, String>) -> String {
    match r {
        Ok(t) => format!("added \"{}\"", t["text"].as_str().unwrap_or("")),
        Err(e) => format!("could not add: {e}"),
    }
}

/// How a toggle reads in the event stream and the journal.
pub fn toggled(id: i64, t: &Option<Value>) -> String {
    match t {
        Some(t) => format!(
            "marked \"{}\" {}",
            t["text"].as_str().unwrap_or(""),
            if t["done"] == true {
                "done"
            } else {
                "not done"
            }
        ),
        None => format!("tried to toggle #{id}"),
    }
}

/// How a remove reads in the event stream and the journal.
pub fn removed(id: i64, t: &Option<String>) -> String {
    match t {
        Some(t) => format!("removed \"{t}\""),
        None => format!("tried to remove #{id}"),
    }
}
