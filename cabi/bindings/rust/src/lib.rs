//! Rust bindings to libautonomous: make a Rust app agent-operable.
//!
//! ```no_run
//! use autonomous::{json, Dial, Runtime};
//!
//! let rt = Runtime::start().unwrap();
//! rt.describe("inventory", "A stock service. Receive goods with inventory_receive.");
//! rt.tool_as("inventory/receive",
//!            "{:description \"Receive qty of sku.\" :input [:map [:sku :string] [:qty [:int {:min 1}]]]}",
//!            |args, who| Ok(json!({"received": args["qty"], "by": who.actor}))).unwrap();
//! rt.serve(&Dial::new("ws://127.0.0.1:7890/autonomous/ws", "aut_..."), |_rt| {});
//! ```
//!
//! The library validates arguments against the tool's malli schema, checks
//! scopes and leases and journals every call; a handler only sees valid
//! arguments. A handler declared with [`Runtime::tool_as`] is also told who
//! called it (a [`Grant`]); one declared with [`Runtime::tool`] is not. The
//! jolt runtime inside is single-threaded: create the `Runtime` on one
//! thread and use it only there (it is `!Send`). Other threads hand work
//! over through the `tick` hook of [`Runtime::serve`].
//!
//! A binary using this crate needs, in its own `build.rs` (cargo does not
//! pass link arguments on from a dependency):
//!
//! ```text
//! println!("cargo:rustc-link-arg=-Wl,-rpath,{lib_dir}");
//! println!("cargo:rustc-link-arg=-Wl,--export-dynamic");
//! ```
//!
//! `--export-dynamic` lets the library find `autonomous_host_dispatch_v2`
//! (and the grant-blind `autonomous_host_dispatch` an older library calls),
//! which this crate defines.

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{c_char, c_int, c_long, c_void, CStr, CString};
use std::io::{Read, Write};
use std::marker::PhantomData;
use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};

pub use serde_json::{json, Value};
use tungstenite::client::IntoClientRequest;
use tungstenite::handshake::HandshakeError;
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{Message, WebSocket};

mod liveness;
pub use liveness::{Due, Link, Liveness, DEFAULT_IDLE_TIMEOUT, DEFAULT_PING_EVERY};

extern "C" {
    fn jolt_library_init(argc: c_int, argv: *mut *mut c_char) -> c_int;
    fn jolt_lookup(name: *const c_char) -> *mut c_void;
}

type S1 = unsafe extern "C" fn(*const c_char) -> *const c_char;
type S2 = unsafe extern "C" fn(*const c_char, *const c_char) -> *const c_char;
type S3 = unsafe extern "C" fn(*const c_char, *const c_char, *const c_char) -> *const c_char;
type I1 = unsafe extern "C" fn(*const c_char) -> c_int;
type L1 = unsafe extern "C" fn(*const c_char) -> c_long;

mod grant;
use grant::{blind_body, grant_body, Body, GrantIn};
pub use grant::{Grant, MAX_SHOWN_ACTOR};
use std::panic::{catch_unwind, AssertUnwindSafe};

/// A tool body: validated arguments in, a JSON result or an error message out.
pub type Handler = Box<dyn FnMut(&Value) -> Result<Value, String>>;

/// A tool body that is also told who called it.
pub type GrantHandler = Box<dyn FnMut(&Value, &Grant) -> Result<Value, String>>;

thread_local! {
    static HANDLERS: RefCell<HashMap<String, Body>> = RefCell::new(HashMap::new());
    static LAST: RefCell<CString> = RefCell::new(CString::default());
}

fn text(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

fn cs(s: &str) -> CString {
    CString::new(s.replace('\0', "")).unwrap()
}

/// Run the handler of `tool`. Its entry is taken out of HANDLERS for the
/// call, so the handler may call back into the runtime (a nested dispatch
/// of another tool), and is put back afterwards unless the handler replaced
/// it. A panic becomes an `{"error": ..}` result and never unwinds into the
/// library.
fn run(tool: *const c_char, args: *const c_char, grant: &GrantIn) -> *const c_char {
    let tool = text(tool);
    let args: Value = serde_json::from_str(&text(args)).unwrap_or(Value::Null);
    let taken = HANDLERS.with(|h| h.borrow_mut().remove(&tool));
    let out = match taken {
        None => {
            json!({"error": {"message": format!("no handler for {tool} (or it is already running)")}})
        }
        Some(mut f) => {
            let r = catch_unwind(AssertUnwindSafe(|| f(&args, grant)));
            HANDLERS.with(|h| {
                h.borrow_mut().entry(tool.clone()).or_insert(f);
            });
            match r {
                Ok(Ok(v)) => v,
                Ok(Err(e)) => json!({"error": {"message": e}}),
                Err(p) => {
                    let why = p
                        .downcast_ref::<&str>()
                        .map(|s| s.to_string())
                        .or_else(|| p.downcast_ref::<String>().cloned())
                        .unwrap_or_else(|| "panic".into());
                    json!({"error": {"message": format!("{tool} panicked: {why}")}})
                }
            }
        }
    };
    LAST.with(|l| {
        *l.borrow_mut() = cs(&out.to_string());
        l.borrow().as_ptr()
    })
}

/// Called by libautonomous 0.3 and later, on the runtime's thread, with
/// validated args and the caller's grant.
#[no_mangle]
pub extern "C" fn autonomous_host_dispatch_v2(
    tool: *const c_char,
    args: *const c_char,
    grant: *const c_char,
) -> *const c_char {
    run(tool, args, &GrantIn::read(&text(grant)))
}

/// Called by libautonomous 0.2, which passes no grant.
#[no_mangle]
pub extern "C" fn autonomous_host_dispatch(
    tool: *const c_char,
    args: *const c_char,
) -> *const c_char {
    run(tool, args, &GrantIn::NotPassed)
}

/// Where and how a runtime dials its bridge.
#[derive(Clone, Debug)]
pub struct Dial {
    /// `ws://` URL of the bridge's runtime endpoint (`.../autonomous/ws`).
    pub url: String,
    /// A runtime token (scope `runtime`) for this app's target.
    pub token: String,
    /// This process in the bridge's directory; keep it across redials.
    pub runtime_id: String,
    /// A label agents see.
    pub label: String,
    /// Ping the bridge after this long without a frame either way.
    pub ping_every: Duration,
    /// Redial after this long without a frame from the bridge.
    pub idle_timeout: Duration,
    /// Give up on a dial (TCP connect, and each read or write of the
    /// websocket upgrade) after this long, so a bridge that accepts the
    /// socket but never answers is redialed.
    pub dial_timeout: Duration,
    /// When the bridge was last heard; clone it to report health.
    pub link: Link,
}

/// How long a dial may take by default; see [`Dial::dial_timeout`].
pub const DEFAULT_DIAL_TIMEOUT: Duration = Duration::from_secs(10);

impl Dial {
    pub fn new(url: &str, token: &str) -> Self {
        Dial {
            url: url.into(),
            token: token.into(),
            runtime_id: format!("rust-{}", std::process::id()),
            label: "rust runtime".into(),
            ping_every: DEFAULT_PING_EVERY,
            idle_timeout: DEFAULT_IDLE_TIMEOUT,
            dial_timeout: DEFAULT_DIAL_TIMEOUT,
            link: Link::default(),
        }
    }
    /// How long a dial may take before it fails and is retried.
    pub fn dial_timeout(mut self, d: Duration) -> Self {
        self.dial_timeout = d;
        self
    }
    /// Report the link's health through `link` (a clone of one the caller keeps).
    pub fn link(mut self, link: Link) -> Self {
        self.link = link;
        self
    }
    /// How often to ping a quiet link, and how long a silent one lives.
    pub fn heartbeat(mut self, ping_every: Duration, idle_timeout: Duration) -> Self {
        self.ping_every = ping_every;
        self.idle_timeout = idle_timeout;
        self
    }
    pub fn runtime_id(mut self, id: &str) -> Self {
        self.runtime_id = id.into();
        self
    }
    pub fn label(mut self, label: &str) -> Self {
        self.label = label.into();
        self
    }
}

/// The autonomous runtime of this process. Use it on one thread only.
pub struct Runtime {
    set_app: I1,
    reg_tool: S2,
    journal: L1,
    rpc: S1,
    ws_hello: S3,
    ws_kind: S1,
    ws_reply: S1,
    _single_thread: PhantomData<*const ()>,
}

fn lookup<T: Copy>(name: &str) -> Result<T, String> {
    let p = unsafe { jolt_lookup(cs(name).as_ptr()) };
    if p.is_null() {
        return Err(format!("libautonomous has no export {name}"));
    }
    Ok(unsafe { std::mem::transmute_copy(&p) })
}

impl Runtime {
    /// Start the runtime (once per process).
    pub fn start() -> Result<Runtime, String> {
        // Keep the dispatchers in the binary for the library to find.
        std::hint::black_box(autonomous_host_dispatch_v2 as *const ());
        std::hint::black_box(autonomous_host_dispatch as *const ());
        if unsafe { jolt_library_init(0, std::ptr::null_mut()) } != 0 {
            return Err("jolt_library_init failed".into());
        }
        Ok(Runtime {
            set_app: lookup("autonomous_set_app")?,
            reg_tool: lookup("autonomous_reg_tool")?,
            journal: lookup("autonomous_journal")?,
            rpc: lookup("autonomous_rpc")?,
            ws_hello: lookup("autonomous_ws_hello")?,
            ws_kind: lookup("autonomous_ws_kind")?,
            ws_reply: lookup("autonomous_ws_reply")?,
            _single_thread: PhantomData,
        })
    }

    /// Name and describe the app; the description is what an agent reads first.
    pub fn describe(&self, name: &str, description: &str) {
        let info = json!({"name": name, "description": description}).to_string();
        unsafe { (self.set_app)(cs(&info).as_ptr()) };
    }

    fn register(&self, id: &str, spec_edn: &str, body: Body) -> Result<String, String> {
        HANDLERS.with(|h| h.borrow_mut().insert(id.to_string(), body));
        let out = text(unsafe { (self.reg_tool)(cs(id).as_ptr(), cs(spec_edn).as_ptr()) });
        let v: Value = serde_json::from_str(&out).unwrap_or(Value::Null);
        match v.get("ok").and_then(Value::as_str) {
            Some(name) => Ok(name.to_string()),
            None => {
                HANDLERS.with(|h| h.borrow_mut().remove(id));
                Err(out)
            }
        }
    }

    /// Declare tool `id` ("todo/add", MCP name todo_add) from an EDN spec
    /// with a malli `:input`; `handler` runs it. Answers the MCP name.
    pub fn tool<F>(&self, id: &str, spec_edn: &str, handler: F) -> Result<String, String>
    where
        F: FnMut(&Value) -> Result<Value, String> + 'static,
    {
        self.register(id, spec_edn, blind_body(handler))
    }

    /// Declare tool `id` like [`Runtime::tool`], with a `handler` that is
    /// told who called it, to attribute what it changes. It fails closed:
    /// a call that reaches it without a grant (a libautonomous older than
    /// 0.3) is refused, and the handler does not run.
    pub fn tool_as<F>(&self, id: &str, spec_edn: &str, handler: F) -> Result<String, String>
    where
        F: FnMut(&Value, &Grant) -> Result<Value, String> + 'static,
    {
        self.register(id, spec_edn, grant_body(id, handler))
    }

    /// Record an app event agents can read, e.g. what a human just did:
    /// `json!({"kind": "todo/added", "by": "human"})`. Answers its sequence number.
    pub fn journal(&self, entry: &Value) -> i64 {
        unsafe { (self.journal)(cs(&entry.to_string()).as_ptr()) as i64 }
    }

    /// Answer one EDN request in process (tests, local tooling).
    pub fn rpc(&self, request_edn: &str) -> String {
        text(unsafe { (self.rpc)(cs(request_edn).as_ptr()) })
    }

    fn hello(&self, d: &Dial) -> String {
        text(unsafe {
            (self.ws_hello)(
                cs(&d.token).as_ptr(),
                cs(&d.runtime_id).as_ptr(),
                cs(&d.label).as_ptr(),
            )
        })
    }

    fn kind(&self, frame: &str) -> String {
        text(unsafe { (self.ws_kind)(cs(frame).as_ptr()) })
    }

    fn reply(&self, frame: &str) -> String {
        text(unsafe { (self.ws_reply)(cs(frame).as_ptr()) })
    }

    /// Dial the bridge and serve agents for good: answer every request
    /// frame, ping a quiet link, redial with backoff under the same runtime
    /// id when the socket drops or the bridge goes silent for
    /// `dial.idle_timeout`, and stop the process on a refusal a redial
    /// would only repeat. `tick` runs on this thread about every 100 ms, so
    /// other threads can hand over work (journal entries, ...).
    pub fn serve(&self, dial: &Dial, mut tick: impl FnMut(&Runtime)) -> ! {
        let mut attempt = 0u32;
        loop {
            match dial_ws(&dial.url, dial.dial_timeout) {
                Ok(mut ws) => {
                    if let MaybeTlsStream::Plain(s) = ws.get_mut() {
                        s.set_read_timeout(Some(Duration::from_millis(100))).ok();
                    }
                    ws.send(Message::text(self.hello(dial))).ok();
                    let mut live =
                        Liveness::new(Instant::now(), dial.ping_every, dial.idle_timeout);
                    let end = session(
                        &mut ws,
                        &mut live,
                        &dial.link,
                        |frame| match self.kind(frame).as_str() {
                            "request" => Step::Reply(self.reply(frame)),
                            "welcome" => {
                                attempt = 0;
                                eprintln!(
                                    "autonomous: {} connected to {}",
                                    dial.runtime_id, dial.url
                                );
                                Step::Stay
                            }
                            "refused" => {
                                eprintln!("autonomous: refused, redialing: {frame}");
                                Step::Redial
                            }
                            "fatal" => Step::Stop(frame.to_string()),
                            _ => Step::Stay,
                        },
                        || tick(self),
                    );
                    match end {
                        End::Fatal(frame) => {
                            eprintln!("autonomous: refused for good: {frame}");
                            std::process::exit(3);
                        }
                        End::Silent(d) => eprintln!(
                            "autonomous: no frame from {} in {}s, redialing",
                            dial.url,
                            d.as_secs()
                        ),
                        End::Dropped => eprintln!("autonomous: lost {}, redialing", dial.url),
                    }
                }
                Err(e) => eprintln!("autonomous: dial {} failed: {e}", dial.url),
            }
            attempt += 1;
            let wait = Duration::from_millis((500u64 << attempt.min(6)).min(30_000));
            let until = Instant::now() + wait;
            while Instant::now() < until {
                tick(self);
                std::thread::sleep(Duration::from_millis(100));
            }
        }
    }
}

/// Open a websocket to `url`, bounding the TCP connect and every read and
/// write of the upgrade by `timeout`: a bridge that accepts the socket but
/// never answers the upgrade fails the dial instead of hanging it. The
/// caller sets the session's own read timeout afterwards.
fn dial_ws(url: &str, timeout: Duration) -> Result<WebSocket<MaybeTlsStream<TcpStream>>, String> {
    let req = url
        .into_client_request()
        .map_err(|e| format!("bad url: {e}"))?;
    let uri = req.uri();
    if uri.scheme_str() == Some("wss") {
        return Err("wss needs TLS, which this crate is built without".into());
    }
    let host = uri.host().ok_or("the url names no host")?;
    let host = host.trim_start_matches('[').trim_end_matches(']');
    let port = uri.port_u16().unwrap_or(80);
    let addrs = (host, port)
        .to_socket_addrs()
        .map_err(|e| format!("resolve {host}: {e}"))?;
    let mut last = format!("{host} resolves to no address");
    let mut stream = None;
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, timeout) {
            Ok(s) => {
                stream = Some(s);
                break;
            }
            Err(e) => last = format!("connect {addr}: {e}"),
        }
    }
    let stream = stream.ok_or(last)?;
    stream.set_read_timeout(Some(timeout)).ok();
    stream.set_write_timeout(Some(timeout)).ok();
    stream.set_nodelay(true).ok();
    match tungstenite::client(req, MaybeTlsStream::Plain(stream)) {
        Ok((ws, _)) => Ok(ws),
        Err(HandshakeError::Failure(e)) => Err(format!("upgrade: {e}")),
        Err(HandshakeError::Interrupted(_)) => Err(format!("no upgrade answer within {timeout:?}")),
    }
}

/// What a session does with one text frame from the bridge.
enum Step {
    /// Keep reading.
    Stay,
    /// Send this text back (nothing when empty), then keep reading.
    Reply(String),
    /// End the session; the caller redials.
    Redial,
    /// End the session for good, with the frame that said so.
    Stop(String),
}

/// How a session with the bridge ended.
#[derive(Debug, PartialEq, Eq)]
enum End {
    /// The socket closed or failed, or the bridge asked for a redial.
    Dropped,
    /// Nothing arrived for this long: the link was dead without saying so.
    Silent(Duration),
    /// The bridge refused for good.
    Fatal(String),
}

fn timed_out(e: &tungstenite::Error) -> bool {
    matches!(e, tungstenite::Error::Io(io)
             if matches!(io.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut))
}

/// Serve one connected socket until it ends. `ws` must have a read timeout
/// so the loop wakes to ping, to check `live` and to run `tick`.
fn session<S: Read + Write>(
    ws: &mut WebSocket<S>,
    live: &mut Liveness,
    link: &Link,
    mut on_text: impl FnMut(&str) -> Step,
    mut tick: impl FnMut(),
) -> End {
    loop {
        match ws.read() {
            Ok(m) => {
                live.heard(Instant::now());
                link.heard();
                match m {
                    Message::Text(t) => match on_text(&t.to_string()) {
                        Step::Stay => {}
                        Step::Reply(r) => {
                            if !r.is_empty() && ws.send(Message::text(r)).is_err() {
                                return End::Dropped;
                            }
                        }
                        Step::Redial => return End::Dropped,
                        Step::Stop(frame) => return End::Fatal(frame),
                    },
                    Message::Close(_) => return End::Dropped,
                    _ => {}
                }
            }
            Err(e) if timed_out(&e) => {}
            Err(_) => return End::Dropped,
        }
        let now = Instant::now();
        match live.due(now) {
            Due::Idle => {}
            Due::Ping => {
                if ws.send(Message::Ping(Default::default())).is_err() {
                    return End::Dropped;
                }
                live.pinged(now);
            }
            Due::Dead => return End::Silent(live.silent_for(now)),
        }
        tick();
    }
}

#[cfg(test)]
mod session_tests {
    use super::*;
    use std::net::{TcpListener, TcpStream};
    use std::thread;

    const MS: Duration = Duration::from_millis(1);

    /// A bridge on a local port that accepts one runtime and then runs `then`.
    fn bridge(then: impl FnOnce(WebSocket<TcpStream>) + Send + 'static) -> String {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("ws://{}/autonomous/ws", l.local_addr().unwrap());
        thread::spawn(move || {
            let (s, _) = l.accept().unwrap();
            then(tungstenite::accept(s).unwrap());
        });
        url
    }

    fn dial(url: &str) -> WebSocket<MaybeTlsStream<TcpStream>> {
        let mut ws = dial_ws(url, 2000 * MS).unwrap();
        if let MaybeTlsStream::Plain(s) = ws.get_mut() {
            s.set_read_timeout(Some(20 * MS)).unwrap();
        }
        ws
    }

    #[test]
    fn a_bridge_that_never_answers_the_upgrade_fails_the_dial() {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("ws://{}/autonomous/ws", l.local_addr().unwrap());
        thread::spawn(move || {
            let (s, _) = l.accept().unwrap();
            thread::sleep(5000 * MS);
            drop(s);
        });
        let t0 = Instant::now();
        let r = dial_ws(&url, 200 * MS);
        assert!(r.is_err(), "the dial should fail");
        assert!(t0.elapsed() < 400 * MS, "took {:?}", t0.elapsed());
    }

    #[test]
    fn a_dial_with_no_listener_fails() {
        let port = TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let r = dial_ws(&format!("ws://127.0.0.1:{port}/autonomous/ws"), 200 * MS);
        assert!(r.is_err());
    }

    #[test]
    fn a_bridge_that_goes_silent_ends_the_session() {
        let url = bridge(|ws| {
            thread::sleep(2000 * MS);
            drop(ws);
        });
        let mut ws = dial(&url);
        let mut live = Liveness::new(Instant::now(), 50 * MS, 300 * MS);
        let t0 = Instant::now();
        let end = session(&mut ws, &mut live, &Link::default(), |_| Step::Stay, || {});
        assert!(matches!(end, End::Silent(_)), "{end:?}");
        assert!(t0.elapsed() < 1000 * MS, "took {:?}", t0.elapsed());
    }

    #[test]
    fn a_bridge_that_answers_pings_keeps_the_session_until_it_closes() {
        let url = bridge(|mut ws| {
            ws.get_mut().set_read_timeout(Some(20 * MS)).unwrap();
            let until = Instant::now() + 600 * MS;
            while Instant::now() < until {
                let _ = ws.read();
                let _ = ws.flush();
            }
            ws.close(None).ok();
            let _ = ws.flush();
            thread::sleep(200 * MS);
        });
        let mut ws = dial(&url);
        let mut live = Liveness::new(Instant::now(), 50 * MS, 300 * MS);
        let end = session(&mut ws, &mut live, &Link::default(), |_| Step::Stay, || {});
        assert_eq!(end, End::Dropped);
    }

    #[test]
    fn a_refusal_ends_the_session_and_a_fatal_one_stops_it() {
        for (frame, want) in [
            ("refused", End::Dropped),
            ("fatal", End::Fatal("fatal".into())),
        ] {
            let url = bridge(move |mut ws| {
                ws.send(Message::text(frame)).unwrap();
                thread::sleep(500 * MS);
            });
            let mut ws = dial(&url);
            let mut live = Liveness::new(Instant::now(), 50 * MS, 300 * MS);
            let end = session(
                &mut ws,
                &mut live,
                &Link::default(),
                |f| match f {
                    "refused" => Step::Redial,
                    other => Step::Stop(other.to_string()),
                },
                || {},
            );
            assert_eq!(end, want);
        }
    }
}

#[cfg(test)]
mod dispatch_tests {
    use super::*;

    fn call(tool: &str, grant: &str) -> Value {
        let (t, a, g) = (cs(tool), cs("{}"), cs(grant));
        serde_json::from_str(&text(autonomous_host_dispatch_v2(
            t.as_ptr(),
            a.as_ptr(),
            g.as_ptr(),
        )))
        .unwrap()
    }

    #[test]
    fn a_panic_is_an_error_result_and_the_tool_stays_registered() {
        HANDLERS.with(|h| {
            h.borrow_mut()
                .insert("t/boom".into(), blind_body(|_| panic!("kaboom")))
        });
        let r = call("t/boom", r#"{"actor":"a","scopes":[]}"#);
        assert!(
            r["error"]["message"].as_str().unwrap().contains("kaboom"),
            "{r}"
        );
        assert!(HANDLERS.with(|h| h.borrow().contains_key("t/boom")));
    }

    #[test]
    fn a_handler_may_dispatch_another_tool() {
        HANDLERS.with(|h| {
            let mut h = h.borrow_mut();
            h.insert("t/inner".into(), blind_body(|_| Ok(json!({"inner": true}))));
            h.insert(
                "t/outer".into(),
                grant_body("t/outer", |_, g| {
                    let inner = call("t/inner", r#"{"actor":"x","scopes":[]}"#);
                    Ok(json!({"outer": g.actor, "inner": inner}))
                }),
            );
        });
        let r = call("t/outer", r#"{"actor":"agent-1","scopes":["write"]}"#);
        assert_eq!(r, json!({"outer": "agent-1", "inner": {"inner": true}}));
    }

    #[test]
    fn an_unreadable_grant_is_refused_by_a_grant_aware_tool() {
        HANDLERS.with(|h| {
            h.borrow_mut().insert(
                "t/aware".into(),
                grant_body("t/aware", |_, _| Ok(json!({}))),
            )
        });
        let r = call("t/aware", "{oops");
        assert!(
            r["error"]["message"]
                .as_str()
                .unwrap()
                .contains("unreadable"),
            "{r}"
        );
    }
}
