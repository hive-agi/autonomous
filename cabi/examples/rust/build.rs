// Link libautonomous (AUTONOMOUS_LIB_DIR, default ../../build) and export the
// executable's symbols, so the library finds autonomous_host_dispatch.
fn main() {
    let dir = std::env::var("AUTONOMOUS_LIB_DIR").unwrap_or_else(|_| {
        let here = std::env::var("CARGO_MANIFEST_DIR").unwrap();
        format!("{here}/../../build")
    });
    println!("cargo:rerun-if-env-changed=AUTONOMOUS_LIB_DIR");
    println!("cargo:rustc-link-search=native={dir}");
    println!("cargo:rustc-link-lib=dylib=autonomous");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{dir}");
    println!("cargo:rustc-link-arg=-Wl,--export-dynamic");
}
