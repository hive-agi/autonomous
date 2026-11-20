// Find libautonomous: AUTONOMOUS_LIB_DIR, default <repo>/cabi/build.
// A binary using this crate also needs, in its own build.rs, the rpath and
// --export-dynamic (cargo does not pass link args on from a dependency):
// see autonomous::build_hint in src/lib.rs.
fn main() {
    let dir = std::env::var("AUTONOMOUS_LIB_DIR").unwrap_or_else(|_| {
        let here = std::env::var("CARGO_MANIFEST_DIR").unwrap();
        format!("{here}/../../build")
    });
    println!("cargo:rerun-if-env-changed=AUTONOMOUS_LIB_DIR");
    println!("cargo:rustc-link-search=native={dir}");
    println!("cargo:rustc-link-lib=dylib=autonomous");
}
