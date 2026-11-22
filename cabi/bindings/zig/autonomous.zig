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
//! Export ONE dispatcher. `exportDispatchAs(f)` (libautonomous 0.3) exports
//! `autonomous_host_dispatch_v2`: `f(tool, args_json, who)` is told who
//! called, a `Grant` valid for that call, and a call without a readable
//! grant fails closed before `f` runs. `exportDispatch(f)` exports the
//! grant-blind `autonomous_host_dispatch` of 0.2. The executable links with
//! -rdynamic so the library finds it. Strings returned by the library stay
//! valid until the next call into it: copy what you keep.
//!
//!     fn dispatchAs(tool: []const u8, args_json: []const u8, who: autonomous.Grant) anyerror![*:0]const u8 { ... }
//!     comptime { autonomous.exportDispatchAs(dispatchAs); }

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

/// The most characters of an actor the library hands a host
/// (AUTONOMOUS_MAX_SHOWN_ACTOR); longer actors are refused, not cut.
pub const max_shown_actor = 64;

/// Who called a tool, as libautonomous 0.3 shows the caller: the actor,
/// never empty, printable ASCII with everything else escaped \uxxxx, and the
/// granted scopes as sorted names. Valid only for the dispatch it is passed to.
pub const Grant = struct {
    actor: []const u8,
    scopes: []const []const u8,

    /// True when the caller holds `scope`, or admin, which covers every scope.
    pub fn has(self: Grant, scope: []const u8) bool {
        for (self.scopes) |s| {
            if (std.mem.eql(u8, s, scope) or std.mem.eql(u8, s, "admin")) return true;
        }
        return false;
    }
};

/// A tool body that is told who called it: the tool id, its validated JSON
/// args and the caller's Grant in, the JSON result out, as for Dispatch.
pub const DispatchAs = *const fn (tool: []const u8, args_json: []const u8, who: Grant) anyerror![*:0]const u8;

/// Why a grant-aware dispatcher refused a call before running.
pub const GrantError = error{ GrantNotPassed, GrantUnreadable };

/// The Grant in `grant_json`, allocated in `gpa` (pass an arena and free it
/// when done); an unparseable grant or one naming no actor is
/// error.GrantUnreadable.
pub fn parseGrant(gpa: std.mem.Allocator, grant_json: []const u8) GrantError!Grant {
    const parsed = std.json.parseFromSliceLeaky(Grant, gpa, grant_json, .{
        .ignore_unknown_fields = true,
    }) catch return error.GrantUnreadable;
    if (parsed.actor.len == 0) return error.GrantUnreadable;
    return parsed;
}

/// Run grant-aware `f` for one call. The Grant lives in an arena owned by
/// this call and freed when `f` returns, so a handler that re-enters the
/// library (rt.rpc to another tool) keeps its own `who`: the nested call
/// parses its grant into its own arena.
pub fn runAs(comptime f: DispatchAs, tool: []const u8, args: []const u8, grant_json: []const u8) [*:0]const u8 {
    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();
    const who = parseGrant(arena.allocator(), grant_json) catch |e| return errorJson(e);
    return f(tool, args, who) catch |e| errorJson(e);
}

/// Export `f` as both dispatchers. libautonomous 0.3 calls
/// autonomous_host_dispatch_v2, and `f` is handed the parsed Grant, valid
/// for that call only (copy what you keep). A 0.2 library calls
/// autonomous_host_dispatch, and the call fails closed
/// (error.GrantNotPassed) without running `f`, as does an unreadable grant
/// (error.GrantUnreadable). A process must hold only one definition of each.
pub fn exportDispatchAs(comptime f: DispatchAs) void {
    const Shim = struct {
        fn v2(tool: [*:0]const u8, args: [*:0]const u8, grant: [*:0]const u8) callconv(.c) [*:0]const u8 {
            return runAs(f, std.mem.span(tool), std.mem.span(args), std.mem.span(grant));
        }
        fn v1(tool: [*:0]const u8, args: [*:0]const u8) callconv(.c) [*:0]const u8 {
            _ = tool;
            _ = args;
            return errorJson(error.GrantNotPassed);
        }
    };
    @export(&Shim.v2, .{ .name = "autonomous_host_dispatch_v2" });
    @export(&Shim.v1, .{ .name = "autonomous_host_dispatch" });
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

/// 0 not started, 1 starting, 2 started: the first caller runs init, any
/// other caller waits for it.
var start_state = std.atomic.Value(u8).init(0);
var started: ?(Error!Runtime) = null;
var init_calls: usize = 0;

/// How many times this process has called jolt_library_init (at most 1).
pub fn initCalls() usize {
    return init_calls;
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

    /// Start the runtime on the calling thread. jolt_library_init runs once
    /// per process: a later call answers the same Runtime (still bound to
    /// the first caller's thread) or the same error.
    pub fn start() Error!Runtime {
        if (start_state.cmpxchgStrong(0, 1, .acquire, .acquire) == null) {
            started = init();
            start_state.store(2, .release);
        } else {
            while (start_state.load(.acquire) != 2) std.atomic.spinLoopHint();
        }
        return started.?;
    }

    fn init() Error!Runtime {
        init_calls += 1;
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

test "parseGrant reads a grant and refuses one naming no actor" {
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const g = try parseGrant(a, "{\"actor\":\"agent-1\",\"scopes\":[\"read\",\"write\"]}");
    try std.testing.expectEqualStrings("agent-1", g.actor);
    try std.testing.expectEqual(@as(usize, 2), g.scopes.len);
    try std.testing.expect(g.has("write") and !g.has("admin"));
    try std.testing.expect((try parseGrant(a, "{\"actor\":\"r\",\"scopes\":[\"admin\"]}")).has("billing"));
    try std.testing.expectError(error.GrantUnreadable, parseGrant(a, "{\"actor\":\"\",\"scopes\":[]}"));
    try std.testing.expectError(error.GrantUnreadable, parseGrant(a, "{\"scopes\":[]}"));
    try std.testing.expectError(error.GrantUnreadable, parseGrant(a, "{oops"));
}

/// A re-entrant handler: "outer" dispatches "inner" for another caller (as a
/// handler calling rt.rpc would), then reports its own `who`.
const Reentrant = struct {
    var outer_buf: [128]u8 = undefined;
    var inner_seen: [64]u8 = undefined;
    var inner_len: usize = 0;

    fn handle(tool: []const u8, args: []const u8, who: Grant) anyerror![*:0]const u8 {
        _ = args;
        if (std.mem.eql(u8, tool, "inner")) {
            inner_len = who.actor.len;
            @memcpy(inner_seen[0..inner_len], who.actor);
            return "{}";
        }
        _ = runAs(handle, "inner", "{}", "{\"actor\":\"agent-inner-with-a-longer-name\",\"scopes\":[\"admin\"]}");
        const out = try std.fmt.bufPrintZ(&outer_buf, "{s}|{d}", .{ who.actor, who.scopes.len });
        return out.ptr;
    }
};

test "a nested dispatch leaves the outer handler's grant intact" {
    const out = std.mem.span(runAs(Reentrant.handle, "outer", "{}", "{\"actor\":\"agent-outer\",\"scopes\":[\"read\",\"write\"]}"));
    try std.testing.expectEqualStrings("agent-outer|2", out);
    try std.testing.expectEqualStrings("agent-inner-with-a-longer-name", Reentrant.inner_seen[0..Reentrant.inner_len]);
}

test "runAs fails closed on an unreadable grant without running the handler" {
    const Never = struct {
        fn handle(_: []const u8, _: []const u8, _: Grant) anyerror![*:0]const u8 {
            return error.ShouldNotRun;
        }
    };
    const out = std.mem.span(runAs(Never.handle, "t", "{}", "{oops"));
    try std.testing.expectEqualStrings("{\"error\":{\"message\":\"GrantUnreadable\"}}", out);
}

test "a dispatch error becomes error JSON" {
    try std.testing.expectEqualStrings("{\"error\":{\"message\":\"Overflow\"}}", std.mem.span(errorJson(error.Overflow)));
}
