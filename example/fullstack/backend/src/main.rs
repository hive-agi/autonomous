//! The todo backend: one store, two doors.
//!
//! - Humans come through the Elm UI: REST under /api, and every change
//!   streams back on /api/events (server-sent events), so every open tab
//!   follows what anyone did.
//! - Agents come through autonomous: the same store as tools (todos_add,
//!   todos_list, ...), served over the bridge's websocket. What humans do is
//!   journaled, so agents can read it.
//!
//! Environment:
//!   TODO_BIND                  HTTP address           [127.0.0.1:8090]
//!   TODO_STATIC                the built frontend     [../frontend/dist]
//!   AUTONOMOUS_WS_URL          the bridge             [ws://127.0.0.1:7890/autonomous/ws]
//!   AUTONOMOUS_BACKEND_TOKEN   runtime token of target "backend"
//!   AUTONOMOUS_FRONTEND_TOKEN  runtime token of target "frontend", handed
//!                              to the browser by /config.json (a local demo;
//!                              a real app gets it from its own login)

use std::io::Read;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread;

use autonomous::{json, Dial, Runtime, Value};
use tiny_http::{Header, Method, Request, Response, Server};

// ---- the store ----------------------------------------------------------------

#[derive(Default)]
struct Store {
    todos: Vec<(i64, String, bool)>,
    next: i64,
}

impl Store {
    fn json(&self) -> Value {
        Value::Array(
            self.todos
                .iter()
                .map(|(id, text, done)| json!({"id": id, "text": text, "done": done}))
                .collect(),
        )
    }
    fn add(&mut self, text: &str) -> Value {
        self.next += 1;
        self.todos.push((self.next, text.to_string(), false));
        json!({"id": self.next, "text": text, "done": false})
    }
    fn toggle(&mut self, id: i64) -> Option<Value> {
        let t = self.todos.iter_mut().find(|t| t.0 == id)?;
        t.2 = !t.2;
        Some(json!({"id": t.0, "text": t.1, "done": t.2}))
    }
    fn remove(&mut self, id: i64) -> Option<String> {
        let i = self.todos.iter().position(|t| t.0 == id)?;
        Some(self.todos.remove(i).1)
    }
    fn clear_done(&mut self) -> usize {
        let before = self.todos.len();
        self.todos.retain(|t| !t.2);
        before - self.todos.len()
    }
}

/// The store, the open event streams, and the journal queue to the
/// autonomous thread. Cheap to clone; shared by every thread.
#[derive(Clone)]
struct Shared {
    store: Arc<Mutex<Store>>,
    streams: Arc<Mutex<Vec<Sender<String>>>>,
    journal: Sender<Value>,
}

impl Shared {
    /// Change the store as `by`, then tell every open tab; a human change is
    /// also journaled for agents.
    fn change<R>(&self, by: &str, what: impl FnOnce(&R) -> String, f: impl FnOnce(&mut Store) -> R) -> R {
        let (r, todos) = {
            let mut s = self.store.lock().unwrap();
            let r = f(&mut s);
            (r, s.json())
        };
        let what = what(&r);
        let event = json!({"todos": todos, "by": by, "what": what}).to_string();
        self.streams.lock().unwrap().retain(|tx| tx.send(event.clone()).is_ok());
        if by == "human" {
            self.journal.send(json!({"kind": "todo/human", "what": what})).ok();
        }
        r
    }
}

// ---- agents: the autonomous thread ----------------------------------------------

fn agent_tools(rt: &Runtime, shared: &Shared) -> Result<(), String> {
    rt.describe(
        "todo-backend",
        "The todo store behind the Elm UI (Rust). todos_list reads it; todos_add, todos_toggle, \
         todos_remove and todos_clear_done change it, and every open browser tab shows the change at once. \
         Humans' edits appear in the journal as todo/human.",
    );
    let s = shared.clone();
    rt.tool(
        "todos/list",
        "{:description \"The todos, optionally only the active or done ones.\" \
          :input [:map [:filter {:optional true} [:enum \"all\" \"active\" \"done\"]]] :read-only? true}",
        move |args| {
            let all = s.store.lock().unwrap().json();
            let want = args["filter"].as_str().unwrap_or("all");
            let keep = |t: &Value| match want {
                "active" => !t["done"].as_bool().unwrap_or(false),
                "done" => t["done"].as_bool().unwrap_or(false),
                _ => true,
            };
            Ok(Value::Array(all.as_array().unwrap().iter().filter(|t| keep(t)).cloned().collect()))
        },
    )?;
    let s = shared.clone();
    rt.tool(
        "todos/add",
        "{:description \"Add a todo. Answers it, with its id.\" :input [:map [:text [:string {:min 1 :max 200}]]]}",
        move |args| {
            let text = args["text"].as_str().unwrap_or_default().trim().to_string();
            Ok(s.change("agent", |t: &Value| format!("added \"{}\"", t["text"].as_str().unwrap_or("")), |st| st.add(&text)))
        },
    )?;
    let s = shared.clone();
    rt.tool(
        "todos/toggle",
        "{:description \"Mark a todo done, or not done again.\" :input [:map [:id :int]]}",
        move |args| {
            let id = args["id"].as_i64().unwrap_or(0);
            s.change(
                "agent",
                |t: &Option<Value>| match t {
                    Some(t) => format!("marked \"{}\" {}", t["text"].as_str().unwrap_or(""), if t["done"] == true { "done" } else { "not done" }),
                    None => format!("tried to toggle #{id}"),
                },
                |st| st.toggle(id),
            )
            .ok_or(format!("no todo #{id}"))
        },
    )?;
    let s = shared.clone();
    rt.tool(
        "todos/remove",
        "{:description \"Delete a todo.\" :input [:map [:id :int]]}",
        move |args| {
            let id = args["id"].as_i64().unwrap_or(0);
            s.change(
                "agent",
                |t: &Option<String>| match t {
                    Some(t) => format!("removed \"{t}\""),
                    None => format!("tried to remove #{id}"),
                },
                |st| st.remove(id),
            )
            .map(|text| json!({"removed": text}))
            .ok_or(format!("no todo #{id}"))
        },
    )?;
    let s = shared.clone();
    rt.tool(
        "todos/clear-done",
        "{:description \"Delete every done todo. Answers how many.\" :input [:map]}",
        move |_| {
            let n = s.change("agent", |n: &usize| format!("cleared {n} done"), |st| st.clear_done());
            Ok(json!({"cleared": n}))
        },
    )?;
    Ok(())
}

// ---- humans: HTTP ----------------------------------------------------------------

fn header(k: &str, v: &str) -> Header {
    Header::from_bytes(k.as_bytes(), v.as_bytes()).unwrap()
}

fn json_response(v: &Value) -> Response<std::io::Cursor<Vec<u8>>> {
    Response::from_string(v.to_string()).with_header(header("Content-Type", "application/json"))
}

/// A server-sent event stream fed by a channel.
struct Events {
    rx: Receiver<String>,
    buf: Vec<u8>,
    pos: usize,
}

impl Read for Events {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if self.pos >= self.buf.len() {
            match self.rx.recv() {
                Ok(e) => {
                    self.buf = format!("data: {e}\n\n").into_bytes();
                    self.pos = 0;
                }
                Err(_) => return Ok(0),
            }
        }
        let n = out.len().min(self.buf.len() - self.pos);
        out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

fn body_json(req: &mut Request) -> Value {
    let mut s = String::new();
    req.as_reader().read_to_string(&mut s).ok();
    serde_json::from_str(&s).unwrap_or(Value::Null)
}

fn serve_static(req: Request, dir: &str, path: &str) {
    let name = if path == "/" { "index.html" } else { path.trim_start_matches('/') };
    let mime = match name.rsplit('.').next() {
        Some("html") => "text/html; charset=utf-8",
        Some("js") => "text/javascript",
        Some("css") => "text/css",
        _ => "application/octet-stream",
    };
    match (!name.contains("..")).then(|| std::fs::read(format!("{dir}/{name}"))) {
        Some(Ok(bytes)) => req.respond(Response::from_data(bytes).with_header(header("Content-Type", mime))).ok(),
        _ => req.respond(Response::from_string("not found").with_status_code(404)).ok(),
    };
}

fn handle(mut req: Request, shared: &Shared, dir: &str, config: &Value) {
    let path = req.url().split('?').next().unwrap_or("/").to_string();
    let parts: Vec<&str> = path.trim_matches('/').split('/').collect();
    let method = req.method().clone();
    let ok = json!({"ok": true});
    match (&method, parts.as_slice()) {
        (Method::Get, ["api", "todos"]) => {
            let todos = shared.store.lock().unwrap().json();
            req.respond(json_response(&todos)).ok();
        }
        (Method::Post, ["api", "todos"]) => {
            let text = body_json(&mut req)["text"].as_str().unwrap_or_default().trim().to_string();
            if text.is_empty() {
                req.respond(Response::from_string("text is required").with_status_code(400)).ok();
            } else {
                let t = shared.change("human", |t: &Value| format!("added \"{}\"", t["text"].as_str().unwrap_or("")), |s| s.add(&text));
                req.respond(json_response(&t)).ok();
            }
        }
        (Method::Post, ["api", "todos", "clear-done"]) => {
            shared.change("human", |n: &usize| format!("cleared {n} done"), |s| s.clear_done());
            req.respond(json_response(&ok)).ok();
        }
        (Method::Post, ["api", "todos", id, "toggle"]) => {
            let id = id.parse().unwrap_or(0);
            let r = shared.change(
                "human",
                |t: &Option<Value>| match t {
                    Some(t) => format!("marked \"{}\" {}", t["text"].as_str().unwrap_or(""), if t["done"] == true { "done" } else { "not done" }),
                    None => format!("tried to toggle #{id}"),
                },
                |s| s.toggle(id),
            );
            req.respond(if r.is_some() { json_response(&ok) } else { json_response(&ok).with_status_code(404) }).ok();
        }
        (Method::Delete, ["api", "todos", id]) => {
            let id = id.parse().unwrap_or(0);
            shared.change(
                "human",
                |t: &Option<String>| match t {
                    Some(t) => format!("removed \"{t}\""),
                    None => format!("tried to remove #{id}"),
                },
                |s| s.remove(id),
            );
            req.respond(json_response(&ok)).ok();
        }
        (Method::Get, ["api", "events"]) => {
            let (tx, rx) = channel();
            shared.streams.lock().unwrap().push(tx);
            let r = Response::new(
                200.into(),
                vec![header("Content-Type", "text/event-stream"), header("Cache-Control", "no-cache")],
                Events { rx, buf: b": open\n\n".to_vec(), pos: 0 },
                None,
                None,
            );
            req.respond(r).ok();
        }
        (Method::Get, ["config.json"]) => {
            req.respond(json_response(config)).ok();
        }
        (Method::Get, _) => serve_static(req, dir, &path),
        _ => {
            req.respond(Response::from_string("not found").with_status_code(404)).ok();
        }
    }
}

fn env(k: &str, default: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| default.to_string())
}

fn main() {
    let bind = env("TODO_BIND", "127.0.0.1:8090");
    let dir = env("TODO_STATIC", "../frontend/dist");
    let ws_url = env("AUTONOMOUS_WS_URL", "ws://127.0.0.1:7890/autonomous/ws");
    let backend_token = env("AUTONOMOUS_BACKEND_TOKEN", "");
    let config = json!({"wsUrl": ws_url, "token": env("AUTONOMOUS_FRONTEND_TOKEN", "")});

    let (journal_tx, journal_rx) = channel::<Value>();
    let shared = Shared { store: Arc::default(), streams: Arc::default(), journal: journal_tx };

    let server = Arc::new(Server::http(&bind).expect("could not bind"));
    eprintln!("todo-backend: http://{bind}");
    for _ in 0..16 {
        let (server, shared, dir, config) = (server.clone(), shared.clone(), dir.clone(), config.clone());
        thread::spawn(move || {
            for req in server.incoming_requests() {
                handle(req, &shared, &dir, &config);
            }
        });
    }

    // This thread owns the autonomous runtime: agent calls run here, and
    // humans' changes reach the journal from here.
    let rt = Runtime::start().expect("libautonomous");
    agent_tools(&rt, &shared).expect("tools");
    if backend_token.is_empty() {
        eprintln!("todo-backend: no AUTONOMOUS_BACKEND_TOKEN, serving humans only");
        loop {
            thread::park();
        }
    }
    let dial = Dial::new(&ws_url, &backend_token).runtime_id("backend-1").label("todo backend (Rust)");
    rt.serve(&dial, |rt| {
        while let Ok(entry) = journal_rx.try_recv() {
            rt.journal(&entry);
        }
    });
}
