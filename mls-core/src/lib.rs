//! mls-core — the single cryptographic core shared by the Android and Windows
//! clients. Phase 0 contains no MLS yet: it proves the build, the C ABI, and the
//! buffer/error/panic contracts in isolation, so Phase 1 can add OpenMLS behind
//! a boundary that is already tested.
//!
//! Why this exists at all: the previous design ran two independent MLS stacks
//! (BouncyCastle on Android, mlspp on Windows) that had to agree byte-for-byte
//! and did not. See docs/mls-v2-architecture.md.

pub mod ffi;
pub mod client;
pub mod group;
pub mod storage;

// Android only: the JNI marshalling layer. Compiled out elsewhere so the
// Windows build carries no JNI dependency.
#[cfg(target_os = "android")]
pub mod jni_api;

/// Bumped whenever the FFI contract changes in a non-additive way. Hosts check
/// this at startup and refuse to run against a core they were not built for -
/// cheaper than diagnosing a silent ABI mismatch on a user's device.
pub const ABI_VERSION: u32 = 1;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
