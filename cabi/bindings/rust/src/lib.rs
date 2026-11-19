//! Rust bindings to libautonomous: make a Rust app agent-operable.
//!
//! ```no_run
//! use autonomous::{json, Dial, Runtime};
//!
//! let rt = Runtime::start().unwrap();
//! rt.describe("inventory", "A stock service. Receive goods with inventory_receive.");
//! rt.tool("inventory/receive",
//!         "{:description \"Receive qty of sku.\" :input [:map [:sku :string] [:qty [:int {:min 1}]]]}",
//!         |args| Ok(json!({"received": args["qty"]}))).unwrap();
//! rt.serve(&Dial::new("ws://127.0.0.1:7890/autonomous/ws", "aut_..."), |_rt| {});
//! ```
//!
//! The library validates arguments against the tool's malli schema, checks
//! scopes and leases and journals every call; a handler only sees valid
//! arguments. The jolt runtime inside is single-threaded: create the
//! `Runtime` on one thread and use it only there (it is `!Send`). Other
//! threads hand work over through the `tick` hook of [`Runtime::serve`].
//!
//! A binary using this crate needs, in its own `build.rs` (cargo does not
//! pass link arguments on from a dependency):
//!
//! ```text
//! println!("cargo:rustc-link-arg=-Wl,-rpath,{lib_dir}");
//! println!("cargo:rustc-link-arg=-Wl,--export-dynamic");
//! ```
//!
//! `--export-dynamic` lets the library find `autonomous_host_dispatch`,
//! which this crate defines.

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{c_char, c_int, c_long, c_void, CStr, CString};
use std::marker::PhantomData;
use std::time::{Duration, Instant};

pub use serde_json::{json, Value};
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{connect, Message};

extern "C" {
    fn jolt_library_init(argc: c_int, argv: *mut *mut c_char) -> c_int;
    fn jolt_lookup(name: *const c_char) -> *mut c_void;
}

type S1 = unsafe extern "C" fn(*const c_char) -> *const c_char;
type S2 = unsafe extern "C" fn(*const c_char, *const c_char) -> *const c_char;
type S3 = unsafe extern "C" fn(*const c_char, *const c_char, *const c_char) -> *const c_char;
type I1 = unsafe extern "C" fn(*const c_char) -> c_int;
type L1 = unsafe extern "C" fn(*const c_char) -> c_long;

/// A tool body: validated arguments in, a JSON result or an error message out.
pub type Handler = Box<dyn FnMut(&Value) -> Result<Value, String>>;

thread_local! {
    static HANDLERS: RefCell<HashMap<String, Handler>> = RefCell::new(HashMap::new());
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

/// Called by libautonomous, on the runtime's thread, with validated args.
#[no_mangle]
pub extern "C" fn autonomous_host_dispatch(tool: *const c_char, args: *const c_char) -> *const c_char {
    let tool = text(tool);
    let args: Value = serde_json::from_str(&text(args)).unwrap_or(Value::Null);
    let out = HANDLERS.with(|h| match h.borrow_mut().get_mut(&tool) {
        Some(f) => match f(&args) {
            Ok(v) => v,
            Err(e) => json!({"error": {"message": e}}),
        },
        None => json!({"error": {"message": format!("no handler for {tool}")}}),
    });
    LAST.with(|l| {
        *l.borrow_mut() = cs(&out.to_string());
        l.borrow().as_ptr()
    })
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
}

impl Dial {
    pub fn new(url: &str, token: &str) -> Self {
        Dial {
            url: url.into(),
            token: token.into(),
            runtime_id: format!("rust-{}", std::process::id()),
            label: "rust runtime".into(),
        }
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
        // Keep the dispatcher in the binary for the library to find.
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

    /// Declare tool `id` ("todo/add", MCP name todo_add) from an EDN spec
    /// with a malli `:input`; `handler` runs it. Answers the MCP name.
    pub fn tool<F>(&self, id: &str, spec_edn: &str, handler: F) -> Result<String, String>
    where
        F: FnMut(&Value) -> Result<Value, String> + 'static,
    {
        HANDLERS.with(|h| h.borrow_mut().insert(id.to_string(), Box::new(handler)));
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
        text(unsafe { (self.ws_hello)(cs(&d.token).as_ptr(), cs(&d.runtime_id).as_ptr(), cs(&d.label).as_ptr()) })
    }

    fn kind(&self, frame: &str) -> String {
        text(unsafe { (self.ws_kind)(cs(frame).as_ptr()) })
    }

    fn reply(&self, frame: &str) -> String {
        text(unsafe { (self.ws_reply)(cs(frame).as_ptr()) })
    }

    /// Dial the bridge and serve agents for good: answer every request
    /// frame, redial with backoff under the same runtime id when the
    /// socket drops, and stop the process on a refusal a redial would
    /// only repeat. `tick` runs on this thread about every 100 ms, so
    /// other threads can hand over work (journal entries, ...).
    pub fn serve(&self, dial: &Dial, mut tick: impl FnMut(&Runtime)) -> ! {
        let mut attempt = 0u32;
        loop {
            match connect(dial.url.as_str()) {
                Ok((mut ws, _)) => {
                    if let MaybeTlsStream::Plain(s) = ws.get_mut() {
                        s.set_read_timeout(Some(Duration::from_millis(100))).ok();
                    }
                    ws.send(Message::text(self.hello(dial))).ok();
                    loop {
                        match ws.read() {
                            Ok(Message::Text(t)) => {
                                let frame = t.to_string();
                                match self.kind(&frame).as_str() {
                                    "request" => {
                                        let r = self.reply(&frame);
                                        if !r.is_empty() && ws.send(Message::text(r)).is_err() {
                                            break;
                                        }
                                    }
                                    "welcome" => {
                                        attempt = 0;
                                        eprintln!("autonomous: {} connected to {}", dial.runtime_id, dial.url);
                                    }
                                    "fatal" => {
                                        eprintln!("autonomous: refused for good: {frame}");
                                        std::process::exit(3);
                                    }
                                    _ => {}
                                }
                            }
                            Ok(Message::Close(_)) => break,
                            Ok(_) => {}
                            Err(tungstenite::Error::Io(e))
                                if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {}
                            Err(_) => break,
                        }
                        tick(self);
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
