//! A Zig app made agent-operable with libautonomous: it registers one tool,
//! dispatches a call through the library (validation, scopes, journal) and
//! prints the answer. Exits 1 when an answer is not the expected one, so
//! `bb cabi-zig` gates the binding. Build: see ../README.md.

const std = @import("std");
const autonomous = @import("autonomous");

extern fn printf(fmt: [*:0]const u8, ...) c_int;

const Args = struct { a: i64, b: i64 };

var out_buf: [256]u8 = undefined;

/// The app's tools. Args arrive as validated JSON, e.g. {"a":2,"b":40}.
fn dispatch(tool: []const u8, args_json: []const u8) [*:0]const u8 {
    if (!std.mem.eql(u8, tool, "calc/add"))
        return "{\"error\":{\"message\":\"unknown tool\"}}";
    const parsed = std.json.parseFromSlice(Args, std.heap.page_allocator, args_json, .{
        .ignore_unknown_fields = true,
    }) catch return "{\"error\":{\"message\":\"bad args\"}}";
    defer parsed.deinit();
    const s = std.fmt.bufPrintZ(&out_buf, "{{\"sum\":{d},\"by\":\"zig\"}}", .{parsed.value.a + parsed.value.b}) catch
        return "{\"error\":{\"message\":\"result too long\"}}";
    return s.ptr;
}

comptime {
    autonomous.exportDispatch(dispatch);
}

var failures: c_int = 0;

fn check(rt: autonomous.Runtime, what: [*:0]const u8, request: [*:0]const u8, want: []const u8) void {
    const got = rt.rpc(request) catch "(wrong thread)";
    const ok = std.mem.indexOf(u8, got, want) != null;
    if (!ok) failures += 1;
    _ = printf("%s %-8s %.*s\n", if (ok) "ok  " else "FAIL", what, @as(c_int, @intCast(got.len)), got.ptr);
}

fn offThread(rt: autonomous.Runtime, refused: *bool) void {
    refused.* = if (rt.rpc("{:op :ping}")) |_| false else |e| e == error.WrongThread;
}

pub fn main() !u8 {
    const rt = try autonomous.Runtime.start();
    try rt.describe("{\"name\":\"calc\",\"description\":\"A Zig calculator. calc_add adds two integers.\"}");

    const reg = rt.tool("calc/add", "{:description \"Add two integers (in Zig)\" :input [:map [:a :int] [:b :int]]}") catch |e| {
        const r = autonomous.lastRefusal();
        _ = printf("FAIL reg      %.*s\n", @as(c_int, @intCast(r.len)), r.ptr);
        return e;
    };
    _ = printf("registered %.*s\n", @as(c_int, @intCast(reg.len)), reg.ptr);

    // "40" is decoded to 40 by the library before the dispatcher sees it.
    check(rt, "call", "{:op :call :tool \"calc_add\" :args {:a 2 :b \"40\"} :scopes #{:write} :actor \"agent-1\"}", ":sum 42");
    check(rt, "invalid", "{:op :call :tool \"calc_add\" :args {:a \"x\"} :scopes #{:write}}", ":invalid-args");
    check(rt, "scope", "{:op :call :tool \"calc_add\" :args {:a 1 :b 2}}", ":forbidden");

    // Off the runtime's thread a call is refused, not run.
    var refused = false;
    const t = try std.Thread.spawn(.{}, offThread, .{ rt, &refused });
    t.join();
    if (refused) {
        _ = printf("ok   thread   another thread is refused\n");
    } else {
        _ = printf("FAIL thread   another thread was not refused\n");
        failures += 1;
    }

    if (failures > 0) {
        _ = printf("\n%d FAILED\n", failures);
        return 1;
    }
    _ = printf("\nall ok\n");
    return 0;
}
