//! An inventory service written in Rust, made agent-operable with libautonomous.
//!
//!   inventory ws://127.0.0.1:7070/autonomous/ws <runtime-token> [runtime-id]
//!
//! It dials the bridge, says hello, and answers every request frame through
//! the library, which validates args, checks scopes, journals the call and
//! calls back into `autonomous_host_dispatch_v2`, with the caller's grant,
//! for the tool itself. Every call
//! into the library happens on the main thread (the jolt runtime's rule).

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::ffi::{c_char, c_int, c_long, c_void, CStr, CString};
use std::time::Duration;

use serde_json::{json, Value};
use tungstenite::{connect, Message};

// ---- the library ------------------------------------------------------------

extern "C" {
    fn jolt_library_init(argc: c_int, argv: *mut *mut c_char) -> c_int;
    fn jolt_lookup(name: *const c_char) -> *mut c_void;
}

type S1 = unsafe extern "C" fn(*const c_char) -> *const c_char;
type S2 = unsafe extern "C" fn(*const c_char, *const c_char) -> *const c_char;
type S3 = unsafe extern "C" fn(*const c_char, *const c_char, *const c_char) -> *const c_char;
type I1 = unsafe extern "C" fn(*const c_char) -> c_int;
type L1 = unsafe extern "C" fn(*const c_char) -> c_long;

struct Autonomous {
    set_app: I1,
    reg_tool: S2,
    journal: L1,
    ws_hello: S3,
    ws_kind: S1,
    ws_reply: S1,
}

fn lookup<T: Copy>(name: &str) -> T {
    let c = CString::new(name).unwrap();
    let p = unsafe { jolt_lookup(c.as_ptr()) };
    assert!(!p.is_null(), "libautonomous has no export {name}");
    unsafe { std::mem::transmute_copy(&p) }
}

fn text(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

fn cs(s: &str) -> CString {
    CString::new(s).unwrap()
}

impl Autonomous {
    fn start() -> Self {
        assert_eq!(unsafe { jolt_library_init(0, std::ptr::null_mut()) }, 0, "jolt init failed");
        Autonomous {
            set_app: lookup("autonomous_set_app"),
            reg_tool: lookup("autonomous_reg_tool"),
            journal: lookup("autonomous_journal"),
            ws_hello: lookup("autonomous_ws_hello"),
            ws_kind: lookup("autonomous_ws_kind"),
            ws_reply: lookup("autonomous_ws_reply"),
        }
    }
    fn set_app(&self, info: Value) {
        unsafe { (self.set_app)(cs(&info.to_string()).as_ptr()) };
    }
    fn reg_tool(&self, id: &str, spec_edn: &str) -> String {
        text(unsafe { (self.reg_tool)(cs(id).as_ptr(), cs(spec_edn).as_ptr()) })
    }
    fn journal(&self, entry: Value) -> i64 {
        unsafe { (self.journal)(cs(&entry.to_string()).as_ptr()) as i64 }
    }
    fn hello(&self, token: &str, id: &str, label: &str) -> String {
        text(unsafe { (self.ws_hello)(cs(token).as_ptr(), cs(id).as_ptr(), cs(label).as_ptr()) })
    }
    fn kind(&self, frame: &str) -> String {
        text(unsafe { (self.ws_kind)(cs(frame).as_ptr()) })
    }
    fn reply(&self, frame: &str) -> String {
        text(unsafe { (self.ws_reply)(cs(frame).as_ptr()) })
    }
}

// ---- the app's own tools --------------------------------------------------------

thread_local! {
    static STOCK: RefCell<BTreeMap<String, i64>> = RefCell::new(BTreeMap::new());
    static LAST: RefCell<CString> = RefCell::new(CString::default());
}

/// Run `tool` with `args` for `grant` ({"actor": .., "scopes": [..]}).
fn run_tool(tool: &str, args: &Value, grant: &Value) -> Value {
    match tool {
        "inventory/receive" => {
            let sku = args["sku"].as_str().unwrap_or_default().to_string();
            let qty = args["qty"].as_i64().unwrap_or(0);
            let now = STOCK.with(|s| {
                let mut s = s.borrow_mut();
                let e = s.entry(sku.clone()).or_insert(0);
                *e += qty;
                *e
            });
            json!({"sku": sku, "on_hand": now, "by": "rust", "actor": grant["actor"]})
        }
        "inventory/stock" => STOCK.with(|s| json!(*s.borrow())),
        other => json!({"error": {"message": format!("no tool {other}")}}),
    }
}

/// Called by libautonomous, on this thread, with validated args and the
/// caller's grant.
#[no_mangle]
pub extern "C" fn autonomous_host_dispatch_v2(
    tool: *const c_char,
    args: *const c_char,
    grant: *const c_char,
) -> *const c_char {
    let tool = text(tool);
    let args: Value = serde_json::from_str(&text(args)).unwrap_or(Value::Null);
    let grant: Value = serde_json::from_str(&text(grant)).unwrap_or(Value::Null);
    let out = run_tool(&tool, &args, &grant).to_string();
    LAST.with(|l| {
        *l.borrow_mut() = CString::new(out).unwrap();
        l.borrow().as_ptr()
    })
}

// ---- wiring ---------------------------------------------------------------------

fn main() {
    let argv: Vec<String> = std::env::args().collect();
    if argv.len() < 3 {
        eprintln!("usage: inventory <ws-url> <runtime-token> [runtime-id]");
        std::process::exit(2);
    }
    let (url, token) = (&argv[1], &argv[2]);
    let runtime_id = argv
        .get(3)
        .cloned()
        .unwrap_or_else(|| format!("inventory-{}", std::process::id()));

    let a = Autonomous::start();
    a.set_app(json!({
        "name": "inventory",
        "description": "A warehouse stock service written in Rust. Receive goods with inventory_receive, read stock with inventory_stock."
    }));
    eprintln!(
        "{}",
        a.reg_tool(
            "inventory/receive",
            "{:description \"Receive qty units of sku into stock. Answers the new on-hand count.\" \
             :input [:map [:sku [:string {:min 1}]] [:qty [:int {:min 1}]]]}"
        )
    );
    eprintln!(
        "{}",
        a.reg_tool(
            "inventory/stock",
            "{:description \"Every sku and its on-hand count.\" :input [:map] :read-only? true}"
        )
    );
    a.journal(json!({"kind": "inventory/started", "runtime": runtime_id}));

    let mut attempt = 0u32;
    loop {
        match connect(url.as_str()) {
            Ok((mut ws, _)) => {
                ws.send(Message::text(a.hello(token, &runtime_id, "rust inventory"))).ok();
                loop {
                    let frame = match ws.read() {
                        Ok(Message::Text(t)) => t.to_string(),
                        Ok(Message::Close(_)) | Err(_) => break,
                        Ok(_) => continue,
                    };
                    match a.kind(&frame).as_str() {
                        "request" => {
                            let r = a.reply(&frame);
                            if !r.is_empty() && ws.send(Message::text(r)).is_err() {
                                break;
                            }
                        }
                        "welcome" => {
                            attempt = 0;
                            eprintln!("inventory: connected as {runtime_id}");
                        }
                        "fatal" => {
                            eprintln!("inventory: refused for good: {frame}");
                            std::process::exit(3);
                        }
                        _ => {}
                    }
                }
            }
            Err(e) => eprintln!("inventory: dial failed: {e}"),
        }
        attempt += 1;
        let ms = (500u64 << attempt.min(6)).min(30_000);
        std::thread::sleep(Duration::from_millis(ms));
    }
}
