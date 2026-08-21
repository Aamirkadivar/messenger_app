//! Generates include/mls_core.h from the FFI surface so the C++ client never
//! hand-maintains a header. A hand-written header that drifts from the library
//! is an ABI mismatch that shows up as memory corruption on a user's machine.

fn main() {
    let crate_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
    let out = std::path::Path::new(&crate_dir).join("include/mls_core.h");

    if let Some(parent) = out.parent() {
        let _ = std::fs::create_dir_all(parent);
    }

    // Header generation is a convenience, not a build requirement: don't fail
    // an Android/CI build just because cbindgen could not run.
    match cbindgen::generate(&crate_dir) {
        Ok(bindings) => {
            bindings.write_to_file(&out);
            println!("cargo:rerun-if-changed=src/ffi.rs");
            println!("cargo:rerun-if-changed=cbindgen.toml");
        }
        Err(e) => println!("cargo:warning=cbindgen failed: {e}"),
    }
}
