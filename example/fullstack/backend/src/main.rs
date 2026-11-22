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
//!   TODO_STATIC                the built frontend     [../frontend/dist];
//!                              only the files in statics::ALLOWED, read
//!                              once at start, are served
//!   AUTONOMOUS_WS_URL          the bridge             [ws://127.0.0.1:7890/autonomous/ws]
//!   AUTONOMOUS_BACKEND_TOKEN   runtime token of target "backend"
//!   AUTONOMOUS_BACKEND_TOKEN_FILE  a file holding it instead (wins; one
//!                              trailing newline is dropped), so the token
//!                              never sits in the process environment
//!   AUTONOMOUS_FRONTEND_TOKEN  runtime token of target "frontend", handed
//!                              to the browser by /config.json (a local demo;
//!                              a real app gets it from its own login)
//!
//! Limits (a public playground sets them all):
//!   TODO_MAX_TODOS             most todos at once      [0 = no limit]
//!   TODO_MAX_TEXT              longest todo, in chars  [200]
//!   TODO_MAX_STREAMS           most open event streams [256]
//!   TODO_RESET_SECS            empty and seed the list every N seconds
//!                                                      [0 = never]
//!   PLAYGROUND_MCP_URL         public MCP endpoint; when set, /config.json
//!   PLAYGROUND_AGENT_TOKEN     tells visitors how to connect their own agent
//!   PLAYGROUND_AGENT_TOKEN_FILE  the same, read from a file (wins)

mod agent_tools;
mod statics;
mod store;

use std::io::{Read, Write};
use std::sync::mpsc::{channel, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use agent_tools::agent_tools;
use autonomous::{json, Dial, Runtime, Value};
use statics::Statics;
use store::{added, removed, toggled, Limits, Store};
use tiny_http::{Header, Method, Request, Response, Server};

/// The largest request body read; the API only ever takes `{"text": ...}`.
const MAX_BODY: u64 = 4096;

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
    fn change<R>(
        &self,
        by: &str,
        what: impl FnOnce(&R) -> String,
        f: impl FnOnce(&mut Store) -> R,
    ) -> R {
        let (r, todos) = {
            let mut s = self.store.lock().unwrap();
            let r = f(&mut s);
            (r, s.json())
        };
        let what = what(&r);
        let event = json!({"todos": todos, "by": by, "what": what}).to_string();
        self.streams
            .lock()
            .unwrap()
            .retain(|tx| tx.send(event.clone()).is_ok());
        if by == "human" {
            self.journal
                .send(json!({"kind": "todo/human", "what": what}))
                .ok();
        }
        r
    }
}

// ---- humans: HTTP ----------------------------------------------------------------

fn header(k: &str, v: &str) -> Header {
    Header::from_bytes(k.as_bytes(), v.as_bytes()).unwrap()
}

fn json_response(v: &Value) -> Response<std::io::Cursor<Vec<u8>>> {
    Response::from_string(v.to_string()).with_header(header("Content-Type", "application/json"))
}

fn text_response(code: u16, s: &str) -> Response<std::io::Cursor<Vec<u8>>> {
    Response::from_string(s).with_status_code(code)
}

fn body_json(req: &mut Request) -> Value {
    let mut s = String::new();
    req.as_reader().take(MAX_BODY).read_to_string(&mut s).ok();
    serde_json::from_str(&s).unwrap_or(Value::Null)
}

fn serve_static(req: Request, statics: &Statics, path: &str) {
    match statics.get(path) {
        Some((mime, bytes)) => req
            .respond(Response::from_data(bytes.to_vec()).with_header(header("Content-Type", mime)))
            .ok(),
        None => req.respond(text_response(404, "not found")).ok(),
    };
}

/// Stream every change to one tab, with a comment line every 25 s so
/// proxies keep the connection open and a closed tab is noticed.
fn event_stream(req: Request, shared: &Shared) {
    let max = shared.store.lock().unwrap().limits.max_streams;
    let (tx, rx) = channel::<String>();
    {
        let mut streams = shared.streams.lock().unwrap();
        if streams.len() >= max {
            drop(streams);
            req.respond(text_response(503, "too many open streams"))
                .ok();
            return;
        }
        streams.push(tx);
    }
    // Written on the raw socket and flushed per event: a chunked tiny_http
    // response would sit in its buffer.
    let mut w = req.into_writer();
    let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\
                Cache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n: open\n\n";
    if w.write_all(head.as_bytes())
        .and_then(|_| w.flush())
        .is_err()
    {
        return;
    }
    thread::spawn(move || loop {
        let chunk = match rx.recv_timeout(Duration::from_secs(25)) {
            Ok(e) => format!("data: {e}\n\n"),
            Err(RecvTimeoutError::Timeout) => ": ping\n\n".to_string(),
            Err(RecvTimeoutError::Disconnected) => break,
        };
        if w.write_all(chunk.as_bytes())
            .and_then(|_| w.flush())
            .is_err()
        {
            break;
        }
    });
}

fn handle(mut req: Request, shared: &Shared, statics: &Statics, config: &Value) {
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
            let text = body_json(&mut req)["text"]
                .as_str()
                .unwrap_or_default()
                .trim()
                .to_string();
            match shared.change("human", added, |s| s.add(&text)) {
                Ok(t) => req.respond(json_response(&t)).ok(),
                Err(e) => req.respond(text_response(400, &e)).ok(),
            };
        }
        (Method::Post, ["api", "todos", "clear-done"]) => {
            shared.change(
                "human",
                |n: &usize| format!("cleared {n} done"),
                |s| s.clear_done(),
            );
            req.respond(json_response(&ok)).ok();
        }
        (Method::Post, ["api", "todos", id, "toggle"]) => {
            let id = id.parse().unwrap_or(0);
            let r = shared.change(
                "human",
                |t: &Option<Value>| toggled(id, t),
                |s| s.toggle(id),
            );
            req.respond(if r.is_some() {
                json_response(&ok)
            } else {
                json_response(&ok).with_status_code(404)
            })
            .ok();
        }
        (Method::Delete, ["api", "todos", id]) => {
            let id = id.parse().unwrap_or(0);
            shared.change(
                "human",
                |t: &Option<String>| removed(id, t),
                |s| s.remove(id),
            );
            req.respond(json_response(&ok)).ok();
        }
        (Method::Get, ["api", "events"]) => event_stream(req, shared),
        (Method::Get, ["config.json"]) => {
            req.respond(json_response(config)).ok();
        }
        (Method::Get, _) => serve_static(req, statics, &path),
        _ => {
            req.respond(text_response(404, "not found")).ok();
        }
    }
}

fn env(k: &str, default: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| default.to_string())
}

/// A secret named `k`: the content of the file `<k>_FILE` when that is set
/// (one trailing newline dropped), else the variable `k`, else "".
/// Exits when the file is set but unreadable: a silent "" would run the
/// backend without its token.
fn secret(k: &str) -> String {
    secret_from(k, |v| std::env::var(v).ok()).unwrap_or_else(|e| {
        eprintln!("todo-backend: {e}");
        std::process::exit(2)
    })
}

fn secret_from(k: &str, var: impl Fn(&str) -> Option<String>) -> Result<String, String> {
    match var(&format!("{k}_FILE")).filter(|p| !p.is_empty()) {
        Some(path) => std::fs::read_to_string(&path)
            .map(|s| s.strip_suffix('\n').unwrap_or(&s).trim_end_matches('\r').to_string())
            .map_err(|e| format!("{k}_FILE {path}: {e}")),
        None => Ok(var(k).unwrap_or_default()),
    }
}

fn env_num(k: &str, default: usize) -> usize {
    std::env::var(k)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

/// What the page learns at start: where its own tab dials (the local demo),
/// and on a playground how a visitor connects their agent.
fn page_config(ws_url: &str, reset_secs: usize, limits: Limits) -> Value {
    let mut config = json!({"wsUrl": ws_url, "token": env("AUTONOMOUS_FRONTEND_TOKEN", "")});
    let mcp_url = env("PLAYGROUND_MCP_URL", "");
    if !mcp_url.is_empty() {
        config["playground"] = json!({
            "mcpUrl": mcp_url,
            "agentToken": secret("PLAYGROUND_AGENT_TOKEN"),
            "resetSecs": reset_secs,
            "maxTodos": limits.max_todos,
            "maxText": limits.max_text,
        });
    }
    config
}

fn main() {
    let bind = env("TODO_BIND", "127.0.0.1:8090");
    let dir = env("TODO_STATIC", "../frontend/dist");
    let ws_url = env("AUTONOMOUS_WS_URL", "ws://127.0.0.1:7890/autonomous/ws");
    let backend_token = secret("AUTONOMOUS_BACKEND_TOKEN");
    let limits = Limits {
        max_todos: env_num("TODO_MAX_TODOS", 0),
        max_text: env_num("TODO_MAX_TEXT", 200),
        max_streams: env_num("TODO_MAX_STREAMS", 256),
    };
    let reset_secs = env_num("TODO_RESET_SECS", 0);
    let config = page_config(&ws_url, reset_secs, limits);
    let (statics, missing) = Statics::load(&dir);
    if !missing.is_empty() {
        eprintln!("todo-backend: not in {dir}, not served: {}", missing.join(" "));
    }
    let statics = Arc::new(statics);

    let (journal_tx, journal_rx) = channel::<Value>();
    let shared = Shared {
        store: Arc::new(Mutex::new(Store::new(limits))),
        streams: Arc::default(),
        journal: journal_tx,
    };
    if reset_secs > 0 {
        shared.store.lock().unwrap().reset();
        let s = shared.clone();
        thread::spawn(move || loop {
            thread::sleep(Duration::from_secs(reset_secs as u64));
            s.change(
                "reset",
                |_: &()| "the list was reset".to_string(),
                |st| st.reset(),
            );
        });
    }

    let server = Arc::new(Server::http(&bind).expect("could not bind"));
    eprintln!("todo-backend: http://{bind}");
    for _ in 0..16 {
        let (server, shared, statics, config) =
            (server.clone(), shared.clone(), statics.clone(), config.clone());
        thread::spawn(move || {
            for req in server.incoming_requests() {
                handle(req, &shared, &statics, &config);
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
    let dial = Dial::new(&ws_url, &backend_token)
        .runtime_id("backend-1")
        .label("todo backend (Rust)");
    rt.serve(&dial, |rt| {
        while let Ok(entry) = journal_rx.try_recv() {
            rt.journal(&entry);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn vars(kv: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let m: HashMap<String, String> =
            kv.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        move |k| m.get(k).cloned()
    }

    #[test]
    fn a_secret_file_wins_over_the_variable_and_loses_its_newline() {
        let f = std::env::temp_dir().join(format!("todo-secret-{}", std::process::id()));
        std::fs::write(&f, "from-file\n").unwrap();
        let p = f.to_string_lossy().into_owned();
        let got = secret_from("T", vars(&[("T", "from-env"), ("T_FILE", &p)]));
        assert_eq!(got, Ok("from-file".to_string()));
        std::fs::write(&f, "crlf\r\n").unwrap();
        assert_eq!(secret_from("T", vars(&[("T_FILE", &p)])), Ok("crlf".to_string()));
        std::fs::remove_file(&f).unwrap();
    }

    #[test]
    fn without_a_file_the_variable_or_nothing() {
        assert_eq!(secret_from("T", vars(&[("T", "v")])), Ok("v".to_string()));
        assert_eq!(secret_from("T", vars(&[("T", "v"), ("T_FILE", "")])), Ok("v".to_string()));
        assert_eq!(secret_from("T", vars(&[])), Ok(String::new()));
    }

    #[test]
    fn an_unreadable_secret_file_is_an_error_not_an_empty_token() {
        let got = secret_from("T", vars(&[("T", "v"), ("T_FILE", "/nonexistent/token")]));
        assert!(got.unwrap_err().starts_with("T_FILE /nonexistent/token"));
    }
}
