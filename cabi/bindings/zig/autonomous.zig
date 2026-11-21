//! Zig bindings to libautonomous: make a Zig app agent-operable.
//!
//!     const autonomous = @import("autonomous");
//!
//!     fn dispatch(tool: []const u8, args_json: []const u8) anyerror![*:0]const u8 { ... }
//!     comptime { autonomous.exportDispatch(dispatch); }
//!
//!     const rt = try autonomous.Runtime.start();
//!     try rt.describe("{\"name\":\"calc\",\"description\":\"A Zig calculator.\"}");
//!     _ = try rt.tool("calc/add", "{:description \"Add\" :input [:map [:a :int] [:b :int]]}");
//!     const answer = try rt.rpc("{:op :call :tool \"calc_add\" :args {:a 1 :b 2} :scopes #{:write}}");
//!
//! The library validates arguments against the tool's malli schema, checks
//! scopes and leases, and journals every call; the app's dispatcher only
//! sees valid arguments. The jolt runtime is single-threaded: start it and
//! make every call from one thread (a Runtime remembers which and refuses
//! the others with error.WrongThread).
//!
//! The app's dispatcher becomes `autonomous_host_dispatch` through
//! `exportDispatch`; the executable links with -rdynamic so the library
//! finds it. Strings returned by the library stay valid until the next call
//! into it: copy what you keep.

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
/// args in, the JSON result out. The result must stay valid until the next
/// dispatch (the library copies it). A returned error becomes the tool
/// error {"error":{"message":"<error name>"}}.
pub const Dispatch = *const fn (tool: []const u8, args_json: []const u8) anyerror![*:0]const u8;

var error_buf: [256]u8 = undefined;

/// The JSON answer for dispatch error `e`.
pub fn errorJson(e: anyerror) [*:0]const u8 {
    const s = std.fmt.bufPrintZ(&error_buf, "{{\"error\":{{\"message\":\"{s}\"}}}}", .{@errorName(e)}) catch
        return "{\"error\":{\"message\":\"dispatch failed\"}}";
    return s.ptr;
}

/// Export `f` as autonomous_host_dispatch, the function the library calls.
/// An error `f` returns is answered as a tool error, so no Zig error crosses
/// into the library.
pub fn exportDispatch(comptime f: Dispatch) void {
    const Shim = struct {
        fn call(tool: [*:0]const u8, args: [*:0]const u8) callconv(.c) [*:0]const u8 {
            return f(std.mem.span(tool), std.mem.span(args)) catch |e| errorJson(e);
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

/// The MCP name of a registration answer {"ok":"<name>"}, or null for any
/// other answer (a refusal is {"error":{...}}).
pub fn registeredName(out: []const u8) ?[]const u8 {
    const prefix = "{\"ok\":\"";
    if (!std.mem.startsWith(u8, out, prefix) or !std.mem.endsWith(u8, out, "\"}")) return null;
    const name = out[prefix.len .. out.len - 2];
    if (name.len == 0 or std.mem.indexOfAny(u8, name, "\"\\") != null) return null;
    return name;
}

var refusal_buf: [4096]u8 = undefined;
var refusal_len: usize = 0;

fn keepRefusal(out: []const u8) void {
    refusal_len = @min(out.len, refusal_buf.len);
    @memcpy(refusal_buf[0..refusal_len], out[0..refusal_len]);
}

/// A copy of the library's answer to the last refused registration (cut at
/// 4 KiB); valid until the next refusal.
pub fn lastRefusal() []const u8 {
    return refusal_buf[0..refusal_len];
}

/// The process's one libautonomous runtime, bound to the thread that started it.
pub const Runtime = struct {
    tid: c_int,
    set_app: I1,
    reg_tool: S2,
    unreg_tool: I1,
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
            .unreg_tool = try lookup(I1, "autonomous_unreg_tool"),
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
    /// Answers the MCP name, valid until the next library call; a refusal
    /// is error.ToolRefused, its answer kept in lastRefusal().
    pub fn tool(self: Runtime, id: [*:0]const u8, spec_edn: [*:0]const u8) Error![]const u8 {
        try self.here();
        const out = text(self.reg_tool(id, spec_edn));
        return registeredName(out) orelse {
            keepRefusal(out);
            return error.ToolRefused;
        };
    }

    /// Unregister tool `id` (or its MCP name).
    pub fn unregister(self: Runtime, id: [*:0]const u8) Error!void {
        try self.here();
        _ = self.unreg_tool(id);
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

test "registeredName takes only an ok answer" {
    try std.testing.expectEqualStrings("calc_add", registeredName("{\"ok\":\"calc_add\"}").?);
    try std.testing.expect(registeredName("{\"error\":{\"message\":\"\\\"ok\\\" is not here\"}}") == null);
    try std.testing.expect(registeredName("{\"error\":{\"type\":\"ok\"}}") == null);
    try std.testing.expect(registeredName("{\"ok\":\"\"}") == null);
    try std.testing.expect(registeredName("") == null);
}

test "lastRefusal owns its bytes" {
    var lib = "{\"error\":{\"message\":\"no\"}}".*;
    keepRefusal(&lib);
    @memset(&lib, 'x');
    try std.testing.expectEqualStrings("{\"error\":{\"message\":\"no\"}}", lastRefusal());
}

test "a dispatch error becomes error JSON" {
    try std.testing.expectEqualStrings("{\"error\":{\"message\":\"Overflow\"}}", std.mem.span(errorJson(error.Overflow)));
}
