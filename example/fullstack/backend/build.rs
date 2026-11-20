// The binary's half of linking libautonomous (see the autonomous crate docs):
// an rpath to the library, and exported symbols so it finds the dispatcher.
fn main() {
    let dir = std::env::var("AUTONOMOUS_LIB_DIR").unwrap_or_else(|_| {
        let here = std::env::var("CARGO_MANIFEST_DIR").unwrap();
        format!("{here}/../../../cabi/build")
    });
    println!("cargo:rerun-if-env-changed=AUTONOMOUS_LIB_DIR");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{dir}");
    println!("cargo:rustc-link-arg=-Wl,--export-dynamic");
}
