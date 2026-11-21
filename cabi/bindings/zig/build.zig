//! zig build        the example, linked against libautonomous
//! zig build run    build and run it (exits 1 on a wrong answer)
//! zig build test   the binding's unit tests
//!
//! -Dlib-dir=<dir> points at libautonomous.so (default ../../build).

const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const lib_dir = b.option([]const u8, "lib-dir", "directory holding libautonomous.so") orelse
        b.pathFromRoot("../../build");

    const autonomous = b.addModule("autonomous", .{
        .root_source_file = b.path("autonomous.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });

    const root = b.createModule(.{
        .root_source_file = b.path("example/main.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{.{ .name = "autonomous", .module = autonomous }},
    });
    root.addLibraryPath(.{ .cwd_relative = lib_dir });
    root.addRPath(.{ .cwd_relative = lib_dir });
    root.linkSystemLibrary("autonomous", .{});

    const exe = b.addExecutable(.{ .name = "zig-example", .root_module = root });
    // The library finds autonomous_host_dispatch in the executable.
    exe.rdynamic = true;
    b.installArtifact(exe);

    const run = b.addRunArtifact(exe);
    b.step("run", "Run the example").dependOn(&run.step);

    const tests = b.addTest(.{ .root_module = autonomous });
    b.step("test", "Run the binding's unit tests").dependOn(&b.addRunArtifact(tests).step);
}
