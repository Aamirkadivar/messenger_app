//! The stable C ABI. This is the ONLY public boundary; Kotlin and C++ see
//! nothing else. Every contract here is deliberate — the previous design leaked
//! library details into both clients and they drifted apart.
//!
//! Contracts (also stated in docs/mls-v2-architecture.md §7):
//!   * Rust allocates every `MlsBuf`; the caller frees it with `mls_buf_free`.
//!     Hosts never call free() on Rust memory directly.
//!   * No panic crosses the boundary — every entry point is wrapped in
//!     `catch_unwind`. A Rust bug must not be undefined behaviour in the host.
//!   * Errors are integer codes. `mls_last_error` is thread-local and must
//!     never contain key material.
//!   * No secret ever crosses this boundary: not private keys, not epoch
//!     secrets, not exporter secrets.
//!   * Additive changes only. New functions, never changed signatures.

use std::cell::RefCell;
use std::ffi::{c_char, c_void, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicPtr, Ordering};

// ---------------------------------------------------------------- error codes

pub const MLS_OK: i32 = 0;
pub const MLS_ERR_INTERNAL: i32 = -1;
pub const MLS_ERR_PANIC: i32 = -2;
pub const MLS_ERR_NULL_ARG: i32 = -3;
pub const MLS_ERR_INVALID_UTF8: i32 = -4;
pub const MLS_ERR_ABI_MISMATCH: i32 = -5;
// Reserved for Phase 1+ so codes stay stable as the API grows:
//   -20..-39 identity / key packages
//   -40..-59 group lifecycle
//   -60..-79 message processing
//   -80..-99 storage

thread_local! {
    static LAST_ERROR: RefCell<Option<CString>> = const { RefCell::new(None) };
}

fn set_last_error(msg: impl Into<String>) {
    // CString::new fails only on interior NULs; fall back rather than panic,
    // since this function runs on the error path already.
    let c = CString::new(msg.into()).unwrap_or_else(|_| CString::new("error").unwrap());
    LAST_ERROR.with(|slot| *slot.borrow_mut() = Some(c));
}

/// Last error for the CALLING THREAD, or NULL. Valid until the next failing
/// call on this thread. Never contains secrets.
#[no_mangle]
pub extern "C" fn mls_last_error() -> *const c_char {
    LAST_ERROR.with(|slot| match slot.borrow().as_ref() {
        Some(c) => c.as_ptr(),
        None => std::ptr::null(),
    })
}

/// Runs `f`, converting a panic into `MLS_ERR_PANIC` instead of unwinding into
/// the host. Every `extern "C"` entry point must go through this.
fn guard<F: FnOnce() -> i32>(f: F) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(code) => code,
        Err(_) => {
            // Deliberately generic: a panic payload can quote internal state.
            set_last_error("panic in mls-core");
            MLS_ERR_PANIC
        }
    }
}

// --------------------------------------------------------------------- buffer

/// Owned byte buffer handed to the host. Free with `mls_buf_free` exactly once.
#[repr(C)]
pub struct MlsBuf {
    pub ptr: *mut u8,
    pub len: usize,
}

impl MlsBuf {
    fn empty() -> Self {
        MlsBuf { ptr: std::ptr::null_mut(), len: 0 }
    }

    fn from_vec(mut v: Vec<u8>) -> Self {
        // Shrink so capacity == len; mls_buf_free reconstructs with len as both,
        // which would leak the difference otherwise.
        v.shrink_to_fit();
        let mut boxed = v.into_boxed_slice();
        let buf = MlsBuf { ptr: boxed.as_mut_ptr(), len: boxed.len() };
        std::mem::forget(boxed);
        buf
    }
}

/// Frees a buffer produced by this library. Passing a NULL ptr is a no-op.
/// Passing anything this library did not allocate is undefined behaviour.
#[no_mangle]
pub unsafe extern "C" fn mls_buf_free(buf: MlsBuf) {
    if buf.ptr.is_null() || buf.len == 0 {
        return;
    }
    drop(Box::from_raw(std::slice::from_raw_parts_mut(buf.ptr, buf.len)));
}

// ------------------------------------------------------------------ log hook

/// Host log sink. Taken by value rather than as `Option<MlsLogCb>`: cbindgen
/// renders an Option-wrapped function pointer as an opaque struct, which is not
/// callable from C at all. Use `mls_clear_log_callback` to detach.
pub type MlsLogCb = extern "C" fn(level: i32, msg: *const c_char);

/// Stored as an atomic rather than `static mut`: the FFI contract promises
/// thread safety, and `static mut` access is unsound under concurrent calls.
static LOG_CB: AtomicPtr<()> = AtomicPtr::new(std::ptr::null_mut());

/// Installs a host log sink. Set this FIRST: without it the core is silent, and
/// a silent diagnostic channel is how the previous design stayed undebuggable
/// for a full session (Qt was dropping qInfo, so the trace never appeared).
#[no_mangle]
pub extern "C" fn mls_set_log_callback(cb: MlsLogCb) {
    LOG_CB.store(cb as *mut (), Ordering::Release);
}

/// Detaches the log sink. Call before unloading the host library so the core
/// cannot call into a function pointer that no longer exists.
#[no_mangle]
pub extern "C" fn mls_clear_log_callback() {
    LOG_CB.store(std::ptr::null_mut(), Ordering::Release);
}

pub(crate) fn host_log(level: i32, msg: &str) {
    let raw = LOG_CB.load(Ordering::Acquire);
    if !raw.is_null() {
        let cb: MlsLogCb = unsafe { std::mem::transmute(raw) };
        if let Ok(c) = CString::new(msg) {
            cb(level, c.as_ptr());
        }
    }
}

// ------------------------------------------------------------------ phase 0

/// ABI version this core was built with. Hosts must compare against their own
/// expected value and refuse to continue on mismatch.
#[no_mangle]
pub extern "C" fn mls_abi_version() -> u32 {
    crate::ABI_VERSION
}

/// Crate version as a NUL-terminated static string. Never freed by the caller.
#[no_mangle]
pub extern "C" fn mls_version() -> *const c_char {
    // Built from a compile-time literal, so the NUL is guaranteed.
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr() as *const c_char
}

/// Phase 0 round-trip: proves string-in / owned-bytes-out, error reporting,
/// panic containment and buffer ownership across JNI and the C ABI before any
/// cryptography exists.
///
/// Echoes `"mls-core <version>: <name>"` into `out`.
///
/// # Safety
/// `name` must be a valid NUL-terminated C string; `out` must be a valid
/// writable pointer. On any error `out` is left as an empty buffer.
#[no_mangle]
pub unsafe extern "C" fn mls_ping(name: *const c_char, out: *mut MlsBuf) -> i32 {
    guard(|| {
        if out.is_null() {
            set_last_error("out is null");
            return MLS_ERR_NULL_ARG;
        }
        // Write a valid empty buffer immediately: if we return early the host
        // can still call mls_buf_free unconditionally.
        *out = MlsBuf::empty();

        if name.is_null() {
            set_last_error("name is null");
            return MLS_ERR_NULL_ARG;
        }
        let name = match CStr::from_ptr(name).to_str() {
            Ok(s) => s,
            Err(_) => {
                set_last_error("name is not valid UTF-8");
                return MLS_ERR_INVALID_UTF8;
            }
        };

        // Exercised by the panic test below; never reachable in normal use.
        if name == "__panic__" {
            panic!("deliberate test panic");
        }

        let reply = format!("mls-core {}: {}", crate::VERSION, name);
        host_log(1, &reply);
        *out = MlsBuf::from_vec(reply.into_bytes());
        MLS_OK
    })
}

// -------------------------------------------------------------- client API
// The same operations Android calls through JNI. Windows loads these with
// QLibrary. Opaque handle; Rust owns the ClientHandle.

use crate::client::ClientHandle;

fn write_buf(out: *mut MlsBuf, data: Vec<u8>) {
    if !out.is_null() {
        unsafe { *out = MlsBuf::from_vec(data) };
    }
}

fn empty_out(out: *mut MlsBuf) {
    if !out.is_null() {
        unsafe { *out = MlsBuf::empty() };
    }
}

fn bytes_in(ptr: *const u8, len: usize) -> Result<&'static [u8], i32> {
    if len == 0 {
        return Ok(&[]);
    }
    if ptr.is_null() {
        set_last_error("null buffer");
        return Err(MLS_ERR_NULL_ARG);
    }
    Ok(unsafe { std::slice::from_raw_parts(ptr, len) })
}

fn cstr_in(p: *const c_char) -> Result<&'static str, i32> {
    if p.is_null() {
        set_last_error("null string");
        return Err(MLS_ERR_NULL_ARG);
    }
    unsafe { CStr::from_ptr(p) }.to_str().map_err(|_| {
        set_last_error("invalid utf-8");
        MLS_ERR_INVALID_UTF8
    })
}

fn as_client(handle: *mut c_void) -> Result<&'static ClientHandle, i32> {
    if handle.is_null() {
        set_last_error("null client handle");
        return Err(MLS_ERR_NULL_ARG);
    }
    Ok(unsafe { &*(handle as *const ClientHandle) })
}

/// Splits a 4-byte-big-endian-length-prefixed KeyPackage list.
fn split_prefixed(blob: &[u8]) -> Result<Vec<Vec<u8>>, i32> {
    let mut out = Vec::new();
    let mut at = 0usize;
    while at + 4 <= blob.len() {
        let len = u32::from_be_bytes([blob[at], blob[at + 1], blob[at + 2], blob[at + 3]]) as usize;
        at += 4;
        if at + len > blob.len() {
            set_last_error("truncated length-prefixed list");
            return Err(MLS_ERR_INTERNAL);
        }
        out.push(blob[at..at + len].to_vec());
        at += len;
    }
    if at != blob.len() {
        set_last_error("trailing bytes in list");
        return Err(MLS_ERR_INTERNAL);
    }
    Ok(out)
}

/// Creates a client. `out_handle` receives an opaque pointer; free with
/// `mls_client_free`.
#[no_mangle]
pub unsafe extern "C" fn mls_client_new(
    user_id: *const c_char,
    device_id: *const c_char,
    out_handle: *mut *mut c_void,
) -> i32 {
    guard(|| {
        if out_handle.is_null() {
            set_last_error("out_handle is null");
            return MLS_ERR_NULL_ARG;
        }
        *out_handle = std::ptr::null_mut();
        let uid = match cstr_in(user_id) {
            Ok(s) => s,
            Err(c) => return c,
        };
        let did = match cstr_in(device_id) {
            Ok(s) => s,
            Err(c) => return c,
        };
        match ClientHandle::new(uid, did) {
            Ok(c) => {
                *out_handle = Box::into_raw(Box::new(c)) as *mut c_void;
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_client_restore(
    blob: *const u8,
    blob_len: usize,
    user_id: *const c_char,
    device_id: *const c_char,
    out_handle: *mut *mut c_void,
) -> i32 {
    guard(|| {
        if out_handle.is_null() {
            set_last_error("out_handle is null");
            return MLS_ERR_NULL_ARG;
        }
        *out_handle = std::ptr::null_mut();
        let bytes = match bytes_in(blob, blob_len) {
            Ok(b) => b,
            Err(c) => return c,
        };
        let uid = match cstr_in(user_id) {
            Ok(s) => s,
            Err(c) => return c,
        };
        let did = match cstr_in(device_id) {
            Ok(s) => s,
            Err(c) => return c,
        };
        match ClientHandle::restore(bytes, uid, did) {
            Ok(c) => {
                *out_handle = Box::into_raw(Box::new(c)) as *mut c_void;
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_client_free(handle: *mut c_void) {
    if !handle.is_null() {
        drop(Box::from_raw(handle as *mut ClientHandle));
    }
}

fn client_bytes(
    handle: *mut c_void,
    out: *mut MlsBuf,
    f: impl FnOnce(&ClientHandle) -> std::result::Result<Vec<u8>, String>,
) -> i32 {
    empty_out(out);
    let c = match as_client(handle) {
        Ok(c) => c,
        Err(code) => return code,
    };
    match f(c) {
        Ok(data) => {
            write_buf(out, data);
            MLS_OK
        }
        Err(e) => {
            set_last_error(e);
            MLS_ERR_INTERNAL
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn mls_client_snapshot(handle: *mut c_void, out: *mut MlsBuf) -> i32 {
    guard(|| client_bytes(handle, out, |c| c.snapshot().map_err(|e| e.to_string())))
}

#[no_mangle]
pub unsafe extern "C" fn mls_client_key_package(handle: *mut c_void, out: *mut MlsBuf) -> i32 {
    guard(|| client_bytes(handle, out, |c| c.key_package().map_err(|e| e.to_string())))
}

#[no_mangle]
pub unsafe extern "C" fn mls_group_create(handle: *mut c_void, gid: *const u8, gid_len: usize) -> i32 {
    guard(|| {
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.create_group(g) {
            Ok(()) => MLS_OK,
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

/// Adds members from a length-prefixed KeyPackage list. Writes
/// `u32be(commit_len) || commit || welcome` into `out`. Staged, not merged.
#[no_mangle]
pub unsafe extern "C" fn mls_group_add(
    handle: *mut c_void,
    gid: *const u8,
    gid_len: usize,
    key_packages: *const u8,
    key_packages_len: usize,
    out: *mut MlsBuf,
) -> i32 {
    guard(|| {
        empty_out(out);
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        let blob = match bytes_in(key_packages, key_packages_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        let kps = match split_prefixed(blob) {
            Ok(v) => v,
            Err(code) => return code,
        };
        if kps.is_empty() {
            set_last_error("no key packages supplied");
            return MLS_ERR_INTERNAL;
        }
        match c.add_members(g, &kps) {
            Ok((commit, welcome)) => {
                let mut packed = Vec::with_capacity(4 + commit.len() + welcome.len());
                packed.extend_from_slice(&(commit.len() as u32).to_be_bytes());
                packed.extend_from_slice(&commit);
                packed.extend_from_slice(&welcome);
                write_buf(out, packed);
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_merge_pending(
    handle: *mut c_void,
    gid: *const u8,
    gid_len: usize,
    out_epoch: *mut u64,
) -> i32 {
    guard(|| {
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.merge_pending(g) {
            Ok(epoch) => {
                if !out_epoch.is_null() {
                    *out_epoch = epoch;
                }
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_clear_pending(handle: *mut c_void, gid: *const u8, gid_len: usize) -> i32 {
    guard(|| {
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.clear_pending(g) {
            Ok(()) => MLS_OK,
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_join_welcome(
    handle: *mut c_void,
    welcome: *const u8,
    welcome_len: usize,
    out_gid: *mut MlsBuf,
) -> i32 {
    guard(|| {
        empty_out(out_gid);
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let w = match bytes_in(welcome, welcome_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.join_from_welcome(w) {
            Ok(gid) => {
                write_buf(out_gid, gid);
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_encrypt(
    handle: *mut c_void,
    gid: *const u8,
    gid_len: usize,
    plaintext: *const u8,
    plaintext_len: usize,
    out: *mut MlsBuf,
) -> i32 {
    guard(|| {
        empty_out(out);
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        let pt = match bytes_in(plaintext, plaintext_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.encrypt(g, pt) {
            Ok(ct) => {
                write_buf(out, ct);
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

/// Processes an incoming MLS message. Writes `[kind] || payload` where kind 1
/// is application plaintext, 2 is a commit (8-byte big-endian epoch), 3 is a
/// proposal.
#[no_mangle]
pub unsafe extern "C" fn mls_process(
    handle: *mut c_void,
    gid: *const u8,
    gid_len: usize,
    msg: *const u8,
    msg_len: usize,
    out: *mut MlsBuf,
) -> i32 {
    guard(|| {
        empty_out(out);
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        let m = match bytes_in(msg, msg_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.process(g, m) {
            Ok((kind, payload)) => {
                let mut packed = Vec::with_capacity(1 + payload.len());
                packed.push(kind);
                packed.extend_from_slice(&payload);
                write_buf(out, packed);
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_epoch(
    handle: *mut c_void,
    gid: *const u8,
    gid_len: usize,
    out_epoch: *mut u64,
) -> i32 {
    guard(|| {
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.epoch(g) {
            Ok(epoch) => {
                if !out_epoch.is_null() {
                    *out_epoch = epoch;
                }
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_load_group(
    handle: *mut c_void,
    gid: *const u8,
    gid_len: usize,
    out_epoch: *mut u64,
) -> i32 {
    guard(|| {
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.load_group(g) {
            Ok(epoch) => {
                if !out_epoch.is_null() {
                    *out_epoch = epoch;
                }
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_roster(handle: *mut c_void, gid: *const u8, gid_len: usize, out: *mut MlsBuf) -> i32 {
    guard(|| {
        empty_out(out);
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.roster(g) {
            Ok(bytes) => {
                write_buf(out, bytes);
                MLS_OK
            }
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mls_drop_group(handle: *mut c_void, gid: *const u8, gid_len: usize) -> i32 {
    guard(|| {
        let c = match as_client(handle) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let g = match bytes_in(gid, gid_len) {
            Ok(b) => b,
            Err(code) => return code,
        };
        match c.drop_group(g) {
            Ok(()) => MLS_OK,
            Err(e) => {
                set_last_error(e.to_string());
                MLS_ERR_INTERNAL
            }
        }
    })
}

// -------------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;

    fn ping(name: &str) -> (i32, String) {
        let c = CString::new(name).unwrap();
        let mut out = MlsBuf::empty();
        let code = unsafe { mls_ping(c.as_ptr(), &mut out) };
        let s = if out.ptr.is_null() {
            String::new()
        } else {
            unsafe { String::from_utf8_lossy(std::slice::from_raw_parts(out.ptr, out.len)).into_owned() }
        };
        unsafe { mls_buf_free(out) };
        (code, s)
    }

    #[test]
    fn round_trip() {
        let (code, s) = ping("kourosh");
        assert_eq!(code, MLS_OK);
        assert!(s.ends_with(": kourosh"), "got {s}");
    }

    #[test]
    fn null_name_is_an_error_not_a_crash() {
        let mut out = MlsBuf::empty();
        let code = unsafe { mls_ping(std::ptr::null(), &mut out) };
        assert_eq!(code, MLS_ERR_NULL_ARG);
        assert!(out.ptr.is_null(), "out must be a safe empty buffer");
        unsafe { mls_buf_free(out) };
    }

    #[test]
    fn null_out_is_an_error_not_a_crash() {
        let c = CString::new("x").unwrap();
        let code = unsafe { mls_ping(c.as_ptr(), std::ptr::null_mut()) };
        assert_eq!(code, MLS_ERR_NULL_ARG);
    }

    /// The contract that matters most: a panic must become an error code, not
    /// undefined behaviour in Kotlin or C++.
    #[test]
    fn panic_is_contained() {
        let (code, s) = ping("__panic__");
        assert_eq!(code, MLS_ERR_PANIC);
        assert!(s.is_empty());
        let msg = unsafe { CStr::from_ptr(mls_last_error()).to_string_lossy().into_owned() };
        assert!(msg.contains("panic"), "got {msg}");
    }

    #[test]
    fn double_free_is_not_possible_via_empty_buf() {
        // Freeing an empty buffer twice is explicitly a no-op.
        unsafe {
            mls_buf_free(MlsBuf::empty());
            mls_buf_free(MlsBuf::empty());
        }
    }

    #[test]
    fn abi_version_is_stable() {
        assert_eq!(mls_abi_version(), 1, "bump ABI_VERSION deliberately");
    }
}
