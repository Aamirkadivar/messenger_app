//! JNI bridge for Android. A thin marshalling layer over the same core the
//! Windows client calls through the C ABI — deliberately NOT a second
//! implementation. If this file ever contains MLS logic, the architecture has
//! been violated (see docs/mls-v2-architecture.md §7).
//!
//! Conventions, chosen to avoid the failure modes of the previous design:
//!   * Binary data is `ByteArray`, never `String`. The old code base had
//!     UTF-16/base64 corruption bugs from passing bytes as text.
//!   * Errors throw `MlsException` carrying the numeric code. Never return null
//!     silently — a silent `joinFromWelcome` failure cost an entire debugging
//!     session.
//!   * Every entry point catches panics; a Rust panic must not unwind into the
//!     JVM.

#![cfg(target_os = "android")]

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

const EXCEPTION_CLASS: &str = "com/messenger/app/data/encryption/MlsException";

/// Throws MlsException(code, message). Falls back to RuntimeException if the
/// app-specific class is missing, so a packaging mistake is still visible.
fn throw(env: &mut JNIEnv, code: i32, msg: &str) {
    let full = format!("{msg} (code {code})");
    if env.throw_new(EXCEPTION_CLASS, &full).is_err() {
        let _ = env.throw_new("java/lang/RuntimeException", &full);
    }
}

/// Android log sink, installed by `nativeInstallLogger`. Routes the core's
/// messages to logcat under a fixed tag so they are greppable.
extern "C" fn android_log(level: i32, msg: *const std::ffi::c_char) {
    if msg.is_null() {
        return;
    }
    let text = unsafe { std::ffi::CStr::from_ptr(msg) }
        .to_string_lossy()
        .into_owned();
    let prio = if level >= 3 {
        log::Level::Warn
    } else {
        log::Level::Info
    };
    log::log!(prio, "{text}");
}

/// ABI version of the loaded core. Kotlin compares it and refuses to run on a
/// mismatch rather than calling into a core it was not built against.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsCore_nativeAbiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::ABI_VERSION as jint
}

#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsCore_nativeVersion<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> JString<'a> {
    match env.new_string(crate::VERSION) {
        Ok(s) => s,
        // Returning an empty string keeps the signature valid; Kotlin treats
        // blank as "unavailable".
        Err(_) => env.new_string("").expect("empty string"),
    }
}

/// Installs the logcat sink. Call once, before anything else.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsCore_nativeInstallLogger(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::ffi::mls_set_log_callback(android_log);
}

/// Phase 0 round-trip. Returns the reply as a ByteArray (bytes, not String, by
/// convention) or throws MlsException.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsCore_nativePing<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    name: JString<'a>,
) -> jbyteArray {
    let null = std::ptr::null_mut();

    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<u8>, (i32, String)> {
        let name: String = env
            .get_string(&name)
            .map_err(|e| (crate::ffi::MLS_ERR_INVALID_UTF8, format!("bad name: {e}")))?
            .into();

        let c = std::ffi::CString::new(name)
            .map_err(|_| (crate::ffi::MLS_ERR_INVALID_UTF8, "interior NUL".to_string()))?;

        let mut buf = crate::ffi::MlsBuf {
            ptr: std::ptr::null_mut(),
            len: 0,
        };
        let rc = unsafe { crate::ffi::mls_ping(c.as_ptr(), &mut buf) };

        // Copy out then free unconditionally: the core guarantees `buf` is a
        // valid (possibly empty) buffer even on error paths.
        let bytes = if buf.ptr.is_null() || buf.len == 0 {
            Vec::new()
        } else {
            unsafe { std::slice::from_raw_parts(buf.ptr, buf.len) }.to_vec()
        };
        unsafe { crate::ffi::mls_buf_free(buf) };

        if rc != crate::ffi::MLS_OK {
            let detail = unsafe {
                let p = crate::ffi::mls_last_error();
                if p.is_null() {
                    "unknown".to_string()
                } else {
                    std::ffi::CStr::from_ptr(p).to_string_lossy().into_owned()
                }
            };
            return Err((rc, detail));
        }
        Ok(bytes)
    }));

    match result {
        Ok(Ok(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                throw(&mut env, crate::ffi::MLS_ERR_INTERNAL, &format!("alloc: {e}"));
                null
            }
        },
        Ok(Err((code, msg))) => {
            throw(&mut env, code, &msg);
            null
        }
        Err(_) => {
            // A panic became an exception rather than unwinding into the JVM.
            throw(&mut env, crate::ffi::MLS_ERR_PANIC, "panic in mls-core");
            null
        }
    }
}

/// Reserved: opaque client handle for Phase 1. Declared now so the Kotlin side
/// can be written against a stable shape.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsCore_nativeClientFree(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) {
    // Superseded by MlsNative_clientFree below; kept so the Phase 0 Kotlin
    // declaration does not break.
}

// ---------------------------------------------------------------- Phase 5
// Real client handles. Kotlin holds an opaque `long`; all MLS state and
// cryptography stay on this side of the boundary.

use crate::client::ClientHandle;
use jni::objects::JByteArray;

/// Borrows the handle without taking ownership. Only `clientFree` may drop it.
unsafe fn as_client(handle: jlong) -> Option<&'static ClientHandle> {
    if handle == 0 {
        None
    } else {
        Some(&*(handle as *const ClientHandle))
    }
}

/// Runs `f` with the client, turning every failure into an MlsException.
/// `fallback` keeps the JNI signature valid on the throw path.
fn with_client<T, F>(env: &mut JNIEnv, handle: jlong, fallback: T, f: F) -> T
where
    F: FnOnce(&ClientHandle) -> std::result::Result<T, String>,
{
    let client = match unsafe { as_client(handle) } {
        Some(c) => c,
        None => {
            throw(env, crate::ffi::MLS_ERR_NULL_ARG, "null client handle");
            return fallback;
        }
    };
    match catch_unwind(AssertUnwindSafe(|| f(client))) {
        Ok(Ok(v)) => v,
        Ok(Err(msg)) => {
            throw(env, crate::ffi::MLS_ERR_INTERNAL, &msg);
            fallback
        }
        Err(_) => {
            throw(env, crate::ffi::MLS_ERR_PANIC, "panic in mls-core");
            fallback
        }
    }
}

fn bytes_of(env: &mut JNIEnv, arr: &JByteArray) -> std::result::Result<Vec<u8>, String> {
    env.convert_byte_array(arr)
        .map_err(|e| format!("read byte array: {e}"))
}

fn out_bytes(env: &mut JNIEnv, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Splits a 4-byte-big-endian-length-prefixed sequence. Used for KeyPackage
/// lists so adding several devices is a single JNI call and a single commit.
fn split_prefixed(blob: &[u8]) -> std::result::Result<Vec<Vec<u8>>, String> {
    let mut out = Vec::new();
    let mut at = 0usize;
    while at + 4 <= blob.len() {
        let len = u32::from_be_bytes([blob[at], blob[at + 1], blob[at + 2], blob[at + 3]]) as usize;
        at += 4;
        if at + len > blob.len() {
            return Err("truncated length-prefixed list".into());
        }
        out.push(blob[at..at + len].to_vec());
        at += len;
    }
    if at != blob.len() {
        return Err("trailing bytes in list".into());
    }
    Ok(out)
}

#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_clientNew<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    user_id: JString<'a>,
    device_id: JString<'a>,
) -> jlong {
    let uid: String = match env.get_string(&user_id) {
        Ok(s) => s.into(),
        Err(e) => {
            throw(&mut env, crate::ffi::MLS_ERR_INVALID_UTF8, &format!("user_id: {e}"));
            return 0;
        }
    };
    let did: String = match env.get_string(&device_id) {
        Ok(s) => s.into(),
        Err(e) => {
            throw(&mut env, crate::ffi::MLS_ERR_INVALID_UTF8, &format!("device_id: {e}"));
            return 0;
        }
    };
    match ClientHandle::new(&uid, &did) {
        Ok(c) => Box::into_raw(Box::new(c)) as jlong,
        Err(e) => {
            throw(&mut env, crate::ffi::MLS_ERR_INTERNAL, &e.to_string());
            0
        }
    }
}

/// Rebuilds a client from a snapshot the host loaded out of secure storage.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_clientRestore<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    blob: JByteArray<'a>,
    user_id: JString<'a>,
    device_id: JString<'a>,
) -> jlong {
    let bytes = match bytes_of(&mut env, &blob) {
        Ok(b) => b,
        Err(e) => {
            throw(&mut env, crate::ffi::MLS_ERR_NULL_ARG, &e);
            return 0;
        }
    };
    let uid: String = match env.get_string(&user_id) {
        Ok(s) => s.into(),
        Err(e) => {
            throw(&mut env, crate::ffi::MLS_ERR_INVALID_UTF8, &format!("user_id: {e}"));
            return 0;
        }
    };
    let did: String = match env.get_string(&device_id) {
        Ok(s) => s.into(),
        Err(e) => {
            throw(&mut env, crate::ffi::MLS_ERR_INVALID_UTF8, &format!("device_id: {e}"));
            return 0;
        }
    };
    match ClientHandle::restore(&bytes, &uid, &did) {
        Ok(c) => Box::into_raw(Box::new(c)) as jlong,
        Err(e) => {
            throw(&mut env, crate::ffi::MLS_ERR_INTERNAL, &e.to_string());
            0
        }
    }
}

/// Frees the handle. Kotlin must call this exactly once, from close().
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_clientFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut ClientHandle)) };
    }
}

/// Snapshot for secure storage. CONTAINS PRIVATE KEY MATERIAL: the host must
/// store it Keystore-wrapped and must never log it or send it to the server.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_snapshot<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
) -> jbyteArray {
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        c.snapshot().map_err(|e| e.to_string())
    });
    if data.is_empty() {
        return std::ptr::null_mut();
    }
    out_bytes(&mut env, &data)
}

#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_keyPackage<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
) -> jbyteArray {
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        c.key_package().map_err(|e| e.to_string())
    });
    if data.is_empty() {
        return std::ptr::null_mut();
    }
    out_bytes(&mut env, &data)
}

#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_groupCreate<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
) {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(e) => {
            throw(&mut env, crate::ffi::MLS_ERR_NULL_ARG, &e);
            return;
        }
    };
    with_client(&mut env, handle, (), |c| {
        c.create_group(&g).map_err(|e| e.to_string())
    });
}

/// Adds devices from a length-prefixed KeyPackage list. Returns
/// `len(commit) || commit || welcome`, so one call yields both blobs.
/// Neither is applied locally until mergePending.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_groupAdd<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
    key_packages: JByteArray<'a>,
) -> jbyteArray {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let kp_blob = match bytes_of(&mut env, &key_packages) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        let kps = split_prefixed(&kp_blob)?;
        if kps.is_empty() {
            return Err("no key packages supplied".into());
        }
        let (commit, welcome) = c.add_members(&g, &kps).map_err(|e| e.to_string())?;
        let mut out = Vec::with_capacity(4 + commit.len() + welcome.len());
        out.extend_from_slice(&(commit.len() as u32).to_be_bytes());
        out.extend_from_slice(&commit);
        out.extend_from_slice(&welcome);
        Ok(out)
    });
    if data.is_empty() {
        return std::ptr::null_mut();
    }
    out_bytes(&mut env, &data)
}

/// Applies a staged commit the Delivery Service accepted. Returns the new epoch.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_mergePending<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
) -> jlong {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    with_client(&mut env, handle, -1i64, |c| {
        c.merge_pending(&g).map(|e| e as jlong).map_err(|e| e.to_string())
    })
}

/// Discards a staged commit the Delivery Service rejected with 409. Without
/// this the client sits on a commit the group never accepted.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_clearPending<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
) {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return,
    };
    with_client(&mut env, handle, (), |c| {
        c.clear_pending(&g).map_err(|e| e.to_string())
    });
}

/// Drops a group the Delivery Service refused (409 orphan). Must remove it
/// from storage so a restart does not resurrect a tree nobody else has.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_dropGroup<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
) {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return,
    };
    with_client(&mut env, handle, (), |c| {
        c.drop_group(&g).map_err(|e| e.to_string())
    });
}

/// Reports whether one KeyPackage could be added, without touching any group.
///
/// `groupAdd` rejects the entire batch if a single package is unusable - a
/// legacy package from another MLS implementation, say - and every package in
/// that batch has already been consumed server-side by then. This lets the host
/// find the bad ones first and add the rest, so one stale device cannot keep
/// healthy devices out of a group.
///
/// Returns JNI_TRUE when valid, JNI_FALSE when not. A malformed or rejected
/// package is an ANSWER here, not an error, so this never throws for a bad
/// package; it throws only when the client handle itself is unusable.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_validateKeyPackage<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    key_package: JByteArray<'a>,
) -> jboolean {
    let kp = match bytes_of(&mut env, &key_package) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    let ok = with_client(&mut env, handle, false, |c| {
        Ok(c.validate_key_package(&kp).is_ok())
    });
    if ok {
        1
    } else {
        0
    }
}

/// The signature public key a KeyPackage's leaf commits to, or an empty array
/// when the package is unusable. Validates first, and touches no group.
///
/// MLS rejects an Add that proposes a signature key already in the tree, or two
/// candidates carrying the same one - and it rejects the WHOLE batch. The host
/// needs this to build a batch that will be accepted, and must get the answer
/// from the core rather than parsing the package itself.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_keyPackageSignatureKey<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    key_package: JByteArray<'a>,
) -> jbyteArray {
    let kp = match bytes_of(&mut env, &key_package) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        Ok(c.key_package_signature_key(&kp).unwrap_or_default())
    });
    out_bytes(&mut env, &data)
}

/// Signature public keys of the group's current members, length-prefixed the
/// same way `groupAdd` takes key packages.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_groupSignatureKeys<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
) -> jbyteArray {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        let keys = c.group_signature_keys(&g).map_err(|e| e.to_string())?;
        let mut out = Vec::new();
        for k in keys {
            out.extend_from_slice(&(k.len() as u32).to_be_bytes());
            out.extend_from_slice(&k);
        }
        Ok(out)
    });
    out_bytes(&mut env, &data)
}

/// Joins from a Welcome. Returns the group id, which the host acks against.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_joinWelcome<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    welcome: JByteArray<'a>,
) -> jbyteArray {
    let w = match bytes_of(&mut env, &welcome) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        c.join_from_welcome(&w).map_err(|e| e.to_string())
    });
    if data.is_empty() {
        return std::ptr::null_mut();
    }
    out_bytes(&mut env, &data)
}

/// Encrypts an application message. The SENDER CANNOT DECRYPT THIS - the host
/// must persist its own plaintext, which is the only readable copy.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_encrypt<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
    plaintext: JByteArray<'a>,
) -> jbyteArray {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let pt = match bytes_of(&mut env, &plaintext) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        c.encrypt(&g, &pt).map_err(|e| e.to_string())
    });
    if data.is_empty() {
        return std::ptr::null_mut();
    }
    out_bytes(&mut env, &data)
}

/// Processes an incoming message. Returns `[kind] || payload`: kind 1 =
/// application (payload is plaintext), 2 = commit (payload is the new epoch as
/// 8 bytes big-endian), 3 = proposal.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_process<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
    msg: JByteArray<'a>,
) -> jbyteArray {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let m = match bytes_of(&mut env, &msg) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        let (kind, payload) = c.process(&g, &m).map_err(|e| e.to_string())?;
        let mut out = Vec::with_capacity(1 + payload.len());
        out.push(kind);
        out.extend_from_slice(&payload);
        Ok(out)
    });
    if data.is_empty() {
        return std::ptr::null_mut();
    }
    out_bytes(&mut env, &data)
}

#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_epoch<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
) -> jlong {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    with_client(&mut env, handle, -1i64, |c| {
        c.epoch(&g).map(|e| e as jlong).map_err(|e| e.to_string())
    })
}

/// Loads a group from restored storage. Returns its epoch. This must NOT
/// produce a commit - see invariant 1 in docs/mls-multi-device.md.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_loadGroup<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
) -> jlong {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    with_client(&mut env, handle, -1i64, |c| {
        c.load_group(&g).map(|e| e as jlong).map_err(|e| e.to_string())
    })
}

/// Newline-separated credentials of the current leaves. The ratchet tree is the
/// only authority on membership - never a locally persisted "invited" set.
#[no_mangle]
pub extern "system" fn Java_com_messenger_app_data_encryption_MlsNative_roster<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
    gid: JByteArray<'a>,
) -> jbyteArray {
    let g = match bytes_of(&mut env, &gid) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let data = with_client(&mut env, handle, Vec::new(), |c| {
        c.roster(&g).map_err(|e| e.to_string())
    });
    out_bytes(&mut env, &data)
}
