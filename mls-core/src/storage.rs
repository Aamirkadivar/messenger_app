//! Local persistence for MLS state.
//!
//! The Go backend is never the source of truth for private MLS state. This
//! module is what makes that possible — and it is the capability the previous
//! design simply did not have: BouncyCastle's group object could not be
//! serialized at all, so a device that created a group could never rebuild it
//! and re-joined on every launch, burning an epoch each time and desyncing
//! every other member.
//!
//! Approach: snapshot OpenMLS's storage map, which is a plain key/value store.
//! We encode it ourselves rather than using `MemoryStorage::serialize`, because
//! that method is gated behind the crate's `test-utils` feature and a
//! test-only code path has no business in a shipping build.
//!
//! Trade-off, stated plainly: this is a whole-store snapshot, not incremental
//! writes. Callers must persist after every state-changing operation. In
//! exchange we avoid hand-implementing OpenMLS's ~40-method `StorageProvider`
//! trait, where a single mistake would be a silent, hard-to-attribute
//! state-corruption bug.

use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::OpenMlsProvider;

/// Format marker. Bumped if the encoding changes, so a stale blob is rejected
/// loudly instead of being misparsed into corrupt state.
const SNAPSHOT_MAGIC: &[u8; 4] = b"MLS1";

#[derive(Debug)]
pub enum StorageError {
    Encode(String),
    Decode(String),
}

impl std::fmt::Display for StorageError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StorageError::Encode(m) => write!(f, "encode: {m}"),
            StorageError::Decode(m) => write!(f, "decode: {m}"),
        }
    }
}

type Result<T> = std::result::Result<T, StorageError>;

fn put_u64(out: &mut Vec<u8>, v: usize) {
    out.extend_from_slice(&(v as u64).to_be_bytes());
}

fn take_u64(input: &[u8], at: &mut usize) -> Result<usize> {
    if *at + 8 > input.len() {
        return Err(StorageError::Decode("truncated length".into()));
    }
    let mut b = [0u8; 8];
    b.copy_from_slice(&input[*at..*at + 8]);
    *at += 8;
    Ok(u64::from_be_bytes(b) as usize)
}

fn take_bytes<'a>(input: &'a [u8], at: &mut usize, len: usize) -> Result<&'a [u8]> {
    if *at + len > input.len() {
        return Err(StorageError::Decode("truncated value".into()));
    }
    let s = &input[*at..*at + len];
    *at += len;
    Ok(s)
}

/// Serializes the provider's entire key store.
///
/// The result contains PRIVATE KEY MATERIAL. The host must place it in
/// OS-backed secure storage (Android Keystore-wrapped, Windows DPAPI) and must
/// never log it, send it to the server, or copy it into a normal file.
pub fn snapshot(provider: &OpenMlsRustCrypto) -> Result<Vec<u8>> {
    let values = provider
        .storage()
        .values
        .read()
        .map_err(|_| StorageError::Encode("storage lock poisoned".into()))?;

    let mut out = Vec::with_capacity(4096);
    out.extend_from_slice(SNAPSHOT_MAGIC);
    put_u64(&mut out, values.len());
    for (k, v) in values.iter() {
        put_u64(&mut out, k.len());
        put_u64(&mut out, v.len());
        out.extend_from_slice(k);
        out.extend_from_slice(v);
    }
    Ok(out)
}

/// Rebuilds a provider from a snapshot. A corrupt or foreign blob is an error,
/// never a partially populated store — half-restored MLS state would fail later
/// as an unattributable decryption error.
pub fn restore(blob: &[u8]) -> Result<OpenMlsRustCrypto> {
    if blob.len() < 4 || &blob[0..4] != SNAPSHOT_MAGIC {
        return Err(StorageError::Decode("bad magic".into()));
    }
    let mut at = 4usize;
    let count = take_u64(blob, &mut at)?;

    // Decode fully before touching the provider, so a failure leaves nothing
    // half-written.
    let mut pairs = Vec::with_capacity(count);
    for _ in 0..count {
        let klen = take_u64(blob, &mut at)?;
        let vlen = take_u64(blob, &mut at)?;
        let k = take_bytes(blob, &mut at, klen)?.to_vec();
        let v = take_bytes(blob, &mut at, vlen)?.to_vec();
        pairs.push((k, v));
    }
    if at != blob.len() {
        return Err(StorageError::Decode("trailing bytes".into()));
    }

    let provider = OpenMlsRustCrypto::default();
    {
        let mut values = provider
            .storage()
            .values
            .write()
            .map_err(|_| StorageError::Decode("storage lock poisoned".into()))?;
        for (k, v) in pairs {
            values.insert(k, v);
        }
    }
    Ok(provider)
}

// ------------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;
    use crate::group::{MlsClient, Processed, CIPHERSUITE};
    use openmls::prelude::tls_codec::{Deserialize, Serialize};
    use openmls::prelude::*;

    fn wire(msg: MlsMessageOut) -> MlsMessageIn {
        let bytes = msg.tls_serialize_detached().expect("serialize");
        MlsMessageIn::tls_deserialize(&mut bytes.as_slice()).expect("deserialize")
    }

    #[test]
    fn snapshot_round_trips() {
        let c = MlsClient::new("alice", "phone").unwrap();
        let _ = c.new_key_package().unwrap();
        let blob = snapshot(&c.provider).unwrap();
        assert!(blob.len() > 4);

        let restored = restore(&blob).unwrap();
        let a = c.provider.storage().values.read().unwrap().len();
        let b = restored.storage().values.read().unwrap().len();
        assert_eq!(a, b, "every entry must survive");
    }

    #[test]
    fn corrupt_snapshot_is_rejected() {
        assert!(restore(b"").is_err());
        assert!(restore(b"XXXX").is_err());

        let c = MlsClient::new("a", "b").unwrap();
        let mut blob = snapshot(&c.provider).unwrap();
        blob.push(0xff); // trailing garbage
        assert!(restore(&blob).is_err(), "must not accept a partial blob");
    }

    /// **Invariant 1 from docs/mls-multi-device.md** — the one the previous
    /// design could never satisfy:
    ///
    ///   restart a device → it rebuilds from disk, epoch unchanged, NO commit.
    ///
    /// v1 logged "cannot rebuild self-created group (needs external join)" on
    /// every launch and re-joined instead, which is what made two devices
    /// leapfrog each other's epochs forever.
    #[test]
    fn group_survives_restart_without_committing() {
        let alice = MlsClient::new("alice", "phone").unwrap();
        let bob = MlsClient::new("bob", "desktop").unwrap();

        // Alice creates the group and adds Bob.
        let mut a_group = alice.create_group(b"chat-restart").unwrap();
        let (_commit, welcome) = alice
            .add_members(&mut a_group, &[bob.new_key_package().unwrap().key_package().clone()])
            .unwrap();
        alice.merge_pending(&mut a_group).unwrap();
        let mut b_group = bob.join_from_welcome(wire(welcome)).unwrap();

        let epoch_before = a_group.epoch().as_u64();
        let group_id = a_group.group_id().clone();

        // Alice "shuts down": persist, then drop everything.
        let blob = snapshot(&alice.provider).unwrap();
        let signer_key = alice.identity.signer.public().to_vec();
        drop(a_group);
        drop(alice);

        // Alice "restarts": restore the provider and load the group from it.
        let provider = restore(&blob).unwrap();
        let signer = openmls_basic_credential::SignatureKeyPair::read(
            provider.storage(),
            &signer_key,
            CIPHERSUITE.signature_algorithm(),
        )
        .expect("signer must survive the restart");

        let mut a_group = MlsGroup::load(provider.storage(), &group_id)
            .expect("load must not error")
            .expect("the group must still be there");

        // The invariant, asserted three ways.
        assert_eq!(a_group.epoch().as_u64(), epoch_before, "epoch must not move");
        assert!(
            a_group.pending_commit().is_none(),
            "restoring must not stage a commit"
        );
        let mut roster: Vec<String> = a_group
            .members()
            .map(|m| String::from_utf8_lossy(m.credential.serialized_content()).into_owned())
            .collect();
        roster.sort();
        assert_eq!(roster, vec!["alice|phone", "bob|desktop"]);

        // And the restored state is genuinely usable: Bob still reads Alice.
        let ct = a_group
            .create_message(&provider, &signer, b"after restart")
            .expect("encrypt with restored keys");
        match bob.process(&mut b_group, wire(ct)).unwrap() {
            Processed::Application(pt) => assert_eq!(pt, b"after restart"),
            _ => panic!("expected an application message"),
        }
    }

    /// The joiner side must survive a restart too — that device holds state
    /// derived from a Welcome it will never see again.
    #[test]
    fn joiner_survives_restart() {
        let alice = MlsClient::new("alice", "phone").unwrap();
        let bob = MlsClient::new("bob", "desktop").unwrap();

        let mut a_group = alice.create_group(b"chat-joiner").unwrap();
        let (_c, welcome) = alice
            .add_members(&mut a_group, &[bob.new_key_package().unwrap().key_package().clone()])
            .unwrap();
        alice.merge_pending(&mut a_group).unwrap();

        let b_group = bob.join_from_welcome(wire(welcome)).unwrap();
        let group_id = b_group.group_id().clone();
        let epoch_before = b_group.epoch().as_u64();

        let blob = snapshot(&bob.provider).unwrap();
        drop(b_group);
        drop(bob);

        let provider = restore(&blob).unwrap();
        let mut b_group = MlsGroup::load(provider.storage(), &group_id)
            .expect("load")
            .expect("joiner's group must persist");
        assert_eq!(b_group.epoch().as_u64(), epoch_before);
        assert!(b_group.pending_commit().is_none());

        // Alice sends; the restored joiner reads it.
        let ct = alice.encrypt(&mut a_group, b"hello restored bob").unwrap();
        let protocol = wire(ct).try_into_protocol_message().unwrap();
        let processed = b_group.process_message(&provider, protocol).expect("process");
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => {
                assert_eq!(app.into_bytes(), b"hello restored bob");
            }
            _ => panic!("expected an application message"),
        }
    }
}
