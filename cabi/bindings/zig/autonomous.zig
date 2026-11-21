//! Zig bindings to libautonomous: make a Zig app agent-operable.
//!
//!     const autonomous = @import("autonomous");
//!
//!     pub const autonomous_dispatch = dispatch; // see Dispatch below
//!     const rt = try autonomous.Runtime.start();
//!     rt.describe("{\"name\":\"calc\",\"description\":\"A Zig calculator.\"}");
//!     _ = try rt.tool("calc/add", "{:description \"Add\" :input [:map [:a :int] [:b :int]]}");
//!     const answer = rt.rpc("{:op :call :tool \"calc_add\" :args {:a 1 :b 2} :scopes #{:write}}");
//!
//! The library validates arguments against the tool's malli schema, checks
//! scopes and leases, and journals every call; the app's dispatcher only
//! sees valid arguments. The jolt runtime is single-threaded: start it and
//! make every call from one thread (a Runtime remembers which and refuses
//! the others with error.WrongThread).
//!
//! Exactly one function crosses back: the app exports
//! `autonomous_host_dispatch` (use `exportDispatch` below) and links with
//! -rdynamic so the library finds it. Strings returned by the library stay
//! valid until the next call into it: copy what you keep.

const std = @import("std");

pub extern fn jolt_library_init(argc: c_int, argv: ?[*]?[*:0]u8) c_int;
pub extern fn jolt_lookup(name: [*:0]const u8) ?*anyopaque;
extern fn gettid() c_int;

const S1 = *const fn ([*:0]const u8) callconv(.c) ?[*:0]const u8;
const S2 = *const fn ([*:0]const u8, [*:0]const u8) callconv(.c) ?[*:0]const u8;
const S3 = *const fn ([*:0]const u8, [*:0]const u8, [*:0]const u8) callconv(.c) ?[*:0]const u8;
const I1 = *const fn ([*:0]const u8) callconv(.c) c_int;
const L1 = *const fn ([*:0]const u8) callconv(.c) c_long;

/// A tool body as the library calls it: the tool id and its validated JSON
/// args in, the JSON result (or {"error":{"message":"..."}}) out. The result
/// must stay valid until the next dispatch; the library copies it.
pub const Dispatch = *const fn (tool: []const u8, args_json: []const u8) [*:0]const u8;

/// Export `f` as autonomous_host_dispatch, the function the library calls:
///     comptime { autonomous.exportDispatch(myDispatch); }
pub fn exportDispatch(comptime f: Dispatch) void {
    const Shim = struct {
        fn call(tool: [*:0]const u8, args: [*:0]const u8) callconv(.c) [*:0]const u8 {
            return f(std.mem.span(tool), std.mem.span(args));
        }
    };
    @export(&Shim.call, .{ .name = "autonomous_host_dispatch" });
}

pub const Error = error{ InitFailed, MissingExport, WrongThread, ToolRefused };

fn lookup(comptime T: type, name: [*:0]const u8) Error!T {
    const p = jolt_lookup(name) orelse return error.MissingExport;
    return @ptrCast(p);
}

fn text(p: ?[*:0]const u8) []const u8 {
    return if (p) |s| std.mem.span(s) else "";
}

/// The process's one libautonomous runtime, bound to the thread that started it.
pub const Runtime = struct {
    tid: c_int,
    set_app: I1,
    reg_tool: S2,
    journal_fn: L1,
    rpc_fn: S1,
    ws_hello: S3,
    ws_kind: S1,
    ws_reply: S1,

    /// Start the runtime, once per process, on the calling thread.
    pub fn start() Error!Runtime {
        if (jolt_library_init(0, null) != 0) return error.InitFailed;
        return .{
            .tid = gettid(),
            .set_app = try lookup(I1, "autonomous_set_app"),
            .reg_tool = try lookup(S2, "autonomous_reg_tool"),
            .journal_fn = try lookup(L1, "autonomous_journal"),
            .rpc_fn = try lookup(S1, "autonomous_rpc"),
            .ws_hello = try lookup(S3, "autonomous_ws_hello"),
            .ws_kind = try lookup(S1, "autonomous_ws_kind"),
            .ws_reply = try lookup(S1, "autonomous_ws_reply"),
        };
    }

    fn here(self: Runtime) Error!void {
        if (gettid() != self.tid) return error.WrongThread;
    }

    /// Describe the app: {"name": ..., "description": ..., "version": ...}.
    pub fn describe(self: Runtime, info_json: [*:0]const u8) Error!void {
        try self.here();
        _ = self.set_app(info_json);
    }

    /// Declare tool `id` ("calc/add", MCP name calc_add) from an EDN spec
    /// with a malli :input (bb tool-spec turns a JSON spec into one).
    /// Answers the library's JSON, {"ok":"<mcp name>"}; a refusal is
    /// error.ToolRefused (read it with lastRefusal).
    pub fn tool(self: Runtime, id: [*:0]const u8, spec_edn: [*:0]const u8) Error![]const u8 {
        try self.here();
        const out = text(self.reg_tool(id, spec_edn));
        if (std.mem.indexOf(u8, out, "\"ok\"") == null) {
            last_refusal = out;
            return error.ToolRefused;
        }
        return out;
    }

    /// Record an app event agents can read: {"kind":"calc/cleared",...}.
    /// Answers its sequence number.
    pub fn journal(self: Runtime, entry_json: [*:0]const u8) Error!i64 {
        try self.here();
        return self.journal_fn(entry_json);
    }

    /// Answer one EDN request in process (tests, local tooling).
    pub fn rpc(self: Runtime, request_edn: [*:0]const u8) Error![]const u8 {
        try self.here();
        return text(self.rpc_fn(request_edn));
    }

    /// The first frame to send on a new websocket to the bridge.
    pub fn wsHello(self: Runtime, token: [*:0]const u8, runtime_id: [*:0]const u8, label: [*:0]const u8) Error![]const u8 {
        try self.here();
        return text(self.ws_hello(token, runtime_id, label));
    }

    /// What a bridge frame asks: request, welcome, refused, fatal or other.
    pub fn wsKind(self: Runtime, frame: [*:0]const u8) Error![]const u8 {
        try self.here();
        return text(self.ws_kind(frame));
    }

    /// The frame answering a bridge frame, "" when none is due; runs the tool.
    pub fn wsReply(self: Runtime, frame: [*:0]const u8) Error![]const u8 {
        try self.here();
        return text(self.ws_reply(frame));
    }
};

var last_refusal: []const u8 = "";

/// The library's answer to the last refused registration.
pub fn lastRefusal() []const u8 {
    return last_refusal;
}
