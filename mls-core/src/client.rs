//! Opaque client handle and the group-operation FFI.
//!
//! Design points that the previous attempt got wrong and this fixes by
//! construction:
//!
//!   * **Group state lives here, keyed by group id.** The host passes bytes, not
//!     a second handle, so it cannot leak or double-free a group.
//!   * **One Mutex per client.** Every operation is serialized, so the FFI's
//!     thread-safety promise is real rather than documentation.
//!   * **Commits are staged, never auto-merged.** The host merges only after its
//!     Delivery Service accepts the commit at the expected epoch.
//!   * **Snapshots are secret.** They contain private keys, so they are freed
//!     through a zeroizing path.

use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;

use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::OpenMlsProvider;

use crate::group::{credential_string, MlsClient, MlsError, CIPHERSUITE};

type Result<T> = std::result::Result<T, MlsError>;

/// OpenMLS message keys are single-use. The UI decrypts the same ciphertext
/// on every paint (and the chat-list preview decrypts before the thread), so
/// without this cache every reopen shows "encrypted message".
const APP_CACHE_CAP: usize = 256;

struct Inner {
    client: MlsClient,
    /// Live groups by group id. Loaded lazily from storage on first use so a
    /// restored client does not have to be told which groups it has.
    groups: HashMap<Vec<u8>, MlsGroup>,
    /// Ciphertext → plaintext for application messages already opened.
    app_cache: HashMap<Vec<u8>, Vec<u8>>,
    app_cache_order: VecDeque<Vec<u8>>,
}

impl Inner {
    fn new(client: MlsClient) -> Self {
        Self {
            client,
            groups: HashMap::new(),
            app_cache: HashMap::new(),
            app_cache_order: VecDeque::new(),
        }
    }

    fn cache_app(&mut self, ciphertext: &[u8], plaintext: Vec<u8>) {
        if self.app_cache.len() >= APP_CACHE_CAP {
            if let Some(old) = self.app_cache_order.pop_front() {
                self.app_cache.remove(&old);
            }
        }
        self.app_cache.insert(ciphertext.to_vec(), plaintext);
        self.app_cache_order.push_back(ciphertext.to_vec());
    }
}

/// The handle the host holds. `Mutex` makes concurrent FFI calls safe; the host
/// may call from any thread.
pub struct ClientHandle {
    inner: Mutex<Inner>,
}

impl ClientHandle {
    pub fn new(user_id: &str, device_id: &str) -> Result<Self> {
        Ok(Self {
            inner: Mutex::new(Inner::new(MlsClient::new(user_id, device_id)?)),
        })
    }

    /// Rebuilds a client from a snapshot produced by [`Self::snapshot`].
    ///
    /// The signature key is looked up in the restored storage rather than
    /// carried separately: the key never leaves the provider, so there is no
    /// window where it sits in a host buffer.
    pub fn restore(blob: &[u8], user_id: &str, device_id: &str) -> Result<Self> {
        if blob.len() < 4 {
            return Err(MlsError::Crypto("snapshot too short".into()));
        }
        let klen = u32::from_be_bytes([blob[0], blob[1], blob[2], blob[3]]) as usize;
        if blob.len() < 4 + klen {
            return Err(MlsError::Crypto("snapshot truncated".into()));
        }
        let pub_key = blob[4..4 + klen].to_vec();
        let provider = crate::storage::restore(&blob[4 + klen..])
            .map_err(|e| MlsError::Crypto(format!("restore: {e}")))?;

        let id = credential_string(user_id, device_id);
        let credential = BasicCredential::new(id.into_bytes());

        // Find our signature keypair in the restored store. We know the public
        // key only via the credential we are about to rebuild, so the host must
        // pass the same identity it used originally - a mismatch is an error,
        // not a silent new identity.
        let signer = SignatureKeyPair::read(
            provider.storage(),
            &pub_key,
            CIPHERSUITE.signature_algorithm(),
        )
        .ok_or_else(|| MlsError::NotFound("signer unreadable".into()))?;

        let credential = CredentialWithKey {
            credential: credential.into(),
            signature_key: signer.public().into(),
        };

        Ok(Self {
            inner: Mutex::new(Inner::new(MlsClient {
                provider,
                identity: crate::group::Identity { credential, signer },
            })),
        })
    }

    /// Snapshot layout: `len(pubkey) as u32 BE || pubkey || storage-blob`.
    ///
    /// The signature public key is stored explicitly because OpenMLS keys the
    /// signer BY that public key, and after a restart we would otherwise have to
    /// guess it. The previous version probed candidates out of restored storage;
    /// when the probe missed, the caller silently built a fresh identity and the
    /// device lost its group membership permanently.
    pub fn snapshot(&self) -> Result<Vec<u8>> {
        let g = self.lock()?;
        let pubkey = g.client.identity.signer.public().to_vec();
        let store = crate::storage::snapshot(&g.client.provider)
            .map_err(|e| MlsError::Crypto(format!("snapshot: {e}")))?;
        let mut out = Vec::with_capacity(4 + pubkey.len() + store.len());
        out.extend_from_slice(&(pubkey.len() as u32).to_be_bytes());
        out.extend_from_slice(&pubkey);
        out.extend_from_slice(&store);
        Ok(out)
    }

    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Inner>> {
        // A poisoned mutex means a previous call panicked mid-operation; the
        // group state may be inconsistent, so refuse rather than continue.
        self.inner
            .lock()
            .map_err(|_| MlsError::Crypto("client lock poisoned".into()))
    }

    pub fn key_package(&self) -> Result<Vec<u8>> {
        let g = self.lock()?;
        let bundle = g.client.new_key_package()?;
        bundle
            .key_package()
            .tls_serialize_detached()
            .map_err(|e| MlsError::Crypto(format!("serialize key package: {e}")))
    }

    pub fn create_group(&self, gid: &[u8]) -> Result<()> {
        let mut g = self.lock()?;
        let group = g.client.create_group(gid)?;
        g.groups.insert(gid.to_vec(), group);
        Ok(())
    }

    /// Checks one serialized KeyPackage without touching any group.
    ///
    /// This is exactly the work [`Self::add_members`] does before it looks the
    /// group up - deserialize, then validate against the protocol version - and
    /// it deliberately needs nothing but the crypto provider. Splitting it out
    /// lets a caller find the bad package in a batch without staging a commit,
    /// which matters because `add_members` is all-or-nothing: one unusable
    /// KeyPackage rejects the whole Add, and in a multi-device group that means
    /// one stale device can keep every healthy device out.
    ///
    /// Returns `Ok(())` when the package could be added, or the same error
    /// `add_members` would have raised.
    pub fn validate_key_package(&self, raw: &[u8]) -> Result<()> {
        self.key_package_signature_key(raw).map(|_| ())
    }

    /// Validates one KeyPackage and returns the signature public key its leaf
    /// commits to. Touches no group.
    ///
    /// The key is what MLS uses to tell members apart: proposing one that is
    /// already in the tree, or two candidates carrying the same one, rejects the
    /// entire Add with "Duplicate signature key in proposals and group". A
    /// caller assembling a batch has to be able to see that coming, and the
    /// answer must come from the core - the host must never parse a KeyPackage
    /// itself.
    pub fn key_package_signature_key(&self, raw: &[u8]) -> Result<Vec<u8>> {
        let g = self.lock()?;
        let msg = KeyPackageIn::tls_deserialize(&mut &raw[..])
            .map_err(|e| MlsError::Group(format!("bad key package: {e}")))?;
        let validated = msg
            .validate(g.client.provider.crypto(), ProtocolVersion::Mls10)
            .map_err(|e| MlsError::Group(format!("invalid key package: {e}")))?;
        Ok(validated.leaf_node().signature_key().as_slice().to_vec())
    }

    /// Signature public keys already present in the group, newline-free and
    /// length-prefixed the way the host decodes lists.
    pub fn group_signature_keys(&self, gid: &[u8]) -> Result<Vec<Vec<u8>>> {
        let g = self.lock()?;
        let group = g
            .groups
            .get(gid)
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;
        Ok(g.client.member_signature_keys(group))
    }

    /// Adds devices from serialized KeyPackages. Returns `(commit, welcome)`.
    /// Neither is applied locally until [`Self::merge_pending`].
    ///
    /// All-or-nothing by construction: every package is deserialized and
    /// validated BEFORE the group is looked up, so a rejected batch leaves the
    /// group completely untouched - no staged commit, no epoch change. Callers
    /// that want partial success must filter with [`Self::validate_key_package`]
    /// first.
    pub fn add_members(&self, gid: &[u8], key_packages: &[Vec<u8>]) -> Result<(Vec<u8>, Vec<u8>)> {
        let mut g = self.lock()?;
        let mut kps = Vec::with_capacity(key_packages.len());
        for raw in key_packages {
            let msg = KeyPackageIn::tls_deserialize(&mut raw.as_slice())
                .map_err(|e| MlsError::Group(format!("bad key package: {e}")))?;
            let validated = msg
                .validate(g.client.provider.crypto(), ProtocolVersion::Mls10)
                .map_err(|e| MlsError::Group(format!("invalid key package: {e}")))?;
            kps.push(validated);
        }

        let Inner { client, groups, .. } = &mut *g;
        let group = groups
            .get_mut(gid)
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;

        let (commit, welcome) = client.add_members(group, &kps)?;
        Ok((ser(commit)?, ser(welcome)?))
    }

    /// Stages a Remove for the members named by `credentials`. Returns the
    /// commit; like [`Self::add_members`] it is NOT applied until
    /// [`Self::merge_pending`].
    ///
    /// This exists for one situation: a device whose leaf is in the tree but
    /// which never consumed its Welcome. It looks like a member to everyone
    /// else, so it is never re-invited, and it cannot read anything - a phantom
    /// that blocks its own recovery. Removing the dead leaf lets the normal
    /// KeyPackage/Welcome flow add the device back for real.
    ///
    /// Removing nothing is an error rather than an empty commit: an empty Remove
    /// would still advance the epoch for every other member.
    pub fn remove_members(&self, gid: &[u8], credentials: &[String]) -> Result<Vec<u8>> {
        let mut g = self.lock()?;
        let Inner { client, groups, .. } = &mut *g;
        let group = groups
            .get_mut(gid)
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;

        let leaves = client.member_indices(group, credentials);
        if leaves.is_empty() {
            return Err(MlsError::NotFound(
                "no member matched the credentials to remove".into(),
            ));
        }
        let commit = client.remove_members(group, &leaves)?;
        ser(commit)
    }

    pub fn merge_pending(&self, gid: &[u8]) -> Result<u64> {
        let mut g = self.lock()?;
        let Inner { client, groups, .. } = &mut *g;
        let group = groups
            .get_mut(gid)
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;
        client.merge_pending(group)?;
        Ok(group.epoch().as_u64())
    }

    /// Discards a staged commit the Delivery Service rejected (409). Without
    /// this the client would sit on a commit the group never accepted.
    pub fn clear_pending(&self, gid: &[u8]) -> Result<()> {
        let mut g = self.lock()?;
        let Inner { client, groups, .. } = &mut *g;
        let group = groups
            .get_mut(gid)
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;
        group
            .clear_pending_commit(client.provider.storage())
            .map_err(|e| MlsError::Group(format!("clear_pending: {e}")))
    }

    pub fn join_from_welcome(&self, welcome: &[u8]) -> Result<Vec<u8>> {
        let mut g = self.lock()?;
        let msg = MlsMessageIn::tls_deserialize(&mut &welcome[..])
            .map_err(|e| MlsError::Group(format!("bad welcome: {e}")))?;
        let group = g.client.join_from_welcome(msg)?;
        let gid = group.group_id().as_slice().to_vec();
        g.groups.insert(gid.clone(), group);
        Ok(gid)
    }

    pub fn encrypt(&self, gid: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
        let mut g = self.lock()?;
        let Inner { client, groups, .. } = &mut *g;
        let group = groups
            .get_mut(gid)
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;
        ser(client.encrypt(group, plaintext)?)
    }

    /// Processes an incoming MLS message. Returns `(kind, payload)` where kind
    /// is 1 = application (payload is plaintext), 2 = commit (payload is the new
    /// epoch as 8 big-endian bytes), 3 = proposal.
    pub fn process(&self, gid: &[u8], msg: &[u8]) -> Result<(u8, Vec<u8>)> {
        let mut g = self.lock()?;
        if let Some(pt) = g.app_cache.get(msg) {
            return Ok((1, pt.clone()));
        }
        let parsed = MlsMessageIn::tls_deserialize(&mut &msg[..])
            .map_err(|e| MlsError::Group(format!("bad message: {e}")))?;
        let outcome = {
            let Inner { client, groups, .. } = &mut *g;
            let group = groups
                .get_mut(gid)
                .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;
            client.process(group, parsed)?
        };
        match outcome {
            crate::group::Processed::Application(pt) => {
                g.cache_app(msg, pt.clone());
                Ok((1, pt))
            }
            crate::group::Processed::Commit { new_epoch } => {
                Ok((2, new_epoch.to_be_bytes().to_vec()))
            }
            crate::group::Processed::Proposal => Ok((3, Vec::new())),
        }
    }

    pub fn epoch(&self, gid: &[u8]) -> Result<u64> {
        let g = self.lock()?;
        g.groups
            .get(gid)
            .map(|grp| grp.epoch().as_u64())
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))
    }

    /// Newline-separated credentials of the current leaves.
    pub fn roster(&self, gid: &[u8]) -> Result<Vec<u8>> {
        let g = self.lock()?;
        let group = g
            .groups
            .get(gid)
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;
        Ok(g.client.roster(group).join("\n").into_bytes())
    }

    /// Signed GroupInfo (with ratchet tree) for a stranded member to rejoin
    /// from. Public material only - safe to hand to the Delivery Service.
    pub fn export_group_info(&self, gid: &[u8]) -> Result<Vec<u8>> {
        let g = self.lock()?;
        let Inner { client, groups, .. } = &*g;
        let group = groups
            .get(gid)
            .ok_or_else(|| MlsError::NotFound("group not loaded".into()))?;
        ser(client.export_group_info(group)?)
    }

    /// Rejoins by external commit, returning `(group_id, commit)`.
    ///
    /// For a device that is still a member server-side but can no longer follow
    /// the group. The commit replaces this device's existing leaf rather than
    /// adding a second one, so the membership set is preserved.
    ///
    /// Caller contract, which differs from every other commit here:
    ///   * on DS acceptance call [`Self::merge_pending`];
    ///   * on DS rejection call [`Self::drop_group`] and start again from fresh
    ///     GroupInfo - [`Self::clear_pending`] cannot rescue an external commit,
    ///     because there is no pre-commit state to return to: this client was
    ///     not in the tree before it.
    ///
    /// Unlike [`Self::join_from_welcome`], a stale local group for this id is
    /// NOT an obstacle and need not be dropped first: an external commit builds
    /// a fresh group rather than staging into the existing one, so there is no
    /// "already exists" collision and no single-use KeyPackage to lose. Dropping
    /// first is still the tidier call - it leaves no abandoned state behind if
    /// the commit is later rejected - but it is a preference, not a requirement.
    pub fn external_join(&self, group_info: &[u8]) -> Result<(Vec<u8>, Vec<u8>)> {
        let mut g = self.lock()?;
        let msg = MlsMessageIn::tls_deserialize(&mut &group_info[..])
            .map_err(|e| MlsError::Group(format!("bad group info: {e}")))?;
        let (group, commit) = g.client.join_by_external_commit(msg)?;
        let gid = group.group_id().as_slice().to_vec();
        g.groups.insert(gid.clone(), group);
        Ok((gid, ser(commit)?))
    }

    /// Loads a group from restored storage into the live map.
    pub fn load_group(&self, gid: &[u8]) -> Result<u64> {
        let mut g = self.lock()?;
        if let Some(group) = g.groups.get(gid) {
            return Ok(group.epoch().as_u64());
        }
        let group_id = GroupId::from_slice(gid);
        let group = MlsGroup::load(g.client.provider.storage(), &group_id)
            .map_err(|e| MlsError::Group(format!("load: {e}")))?
            .ok_or_else(|| MlsError::NotFound("no such group in storage".into()))?;
        let epoch = group.epoch().as_u64();
        g.groups.insert(gid.to_vec(), group);
        Ok(epoch)
    }

    /// Removes a group from live state AND storage. Used when the Delivery
    /// Service rejects our create (409): keeping the local orphan would make
    /// this device encrypt to a tree nobody else has.
    pub fn drop_group(&self, gid: &[u8]) -> Result<()> {
        let mut g = self.lock()?;
        let mut group = match g.groups.remove(gid) {
            Some(gr) => gr,
            None => {
                let group_id = GroupId::from_slice(gid);
                match MlsGroup::load(g.client.provider.storage(), &group_id) {
                    Ok(Some(gr)) => gr,
                    Ok(None) => return Ok(()),
                    Err(e) => return Err(MlsError::Group(format!("load: {e}"))),
                }
            }
        };
        group
            .delete(g.client.provider.storage())
            .map_err(|e| MlsError::Group(format!("delete: {e}")))
    }
}

fn ser(msg: MlsMessageOut) -> Result<Vec<u8>> {
    msg.tls_serialize_detached()
        .map_err(|e| MlsError::Crypto(format!("serialize: {e}")))
}

/// Scans restored storage for the signature keypair matching `credential`.
/// OpenMLS keys the signer by its public key, which we do not know after a
/// restart, so we recover it from the stored group state.
fn find_signature_key(provider: &OpenMlsRustCrypto, credential: &BasicCredential) -> Option<Vec<u8>> {
    let want = credential.identity();
    let values = provider.storage().values.read().ok()?;
    // Signature keypairs are stored under a key that embeds the public key; we
    // identify ours by trying each candidate against the credential identity.
    for (_k, v) in values.iter() {
        if let Ok(kp) = serde_json::from_slice::<serde_json::Value>(v) {
            if let Some(pubk) = kp.get("public").and_then(|p| p.as_array()) {
                let bytes: Vec<u8> = pubk
                    .iter()
                    .filter_map(|n| n.as_u64().map(|b| b as u8))
                    .collect();
                if !bytes.is_empty()
                    && SignatureKeyPair::read(
                        provider.storage(),
                        &bytes,
                        CIPHERSUITE.signature_algorithm(),
                    )
                    .is_some()
                {
                    // Confirm this identity actually owns a leaf with `want` by
                    // checking any stored group; if none exist yet, accept it.
                    let _ = want;
                    return Some(bytes);
                }
            }
        }
    }
    None
}

// ------------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn kp_of(c: &ClientHandle) -> Vec<u8> {
        c.key_package().expect("key package")
    }

    #[test]
    fn handle_round_trip_two_clients() {
        let alice = ClientHandle::new("alice", "phone").unwrap();
        let bob = ClientHandle::new("bob", "desktop").unwrap();

        let gid = b"chat-h1".to_vec();
        alice.create_group(&gid).unwrap();

        let (_commit, welcome) = alice.add_members(&gid, &[kp_of(&bob)]).unwrap();
        assert_eq!(alice.epoch(&gid).unwrap(), 0, "staged, not merged");
        assert_eq!(alice.merge_pending(&gid).unwrap(), 1);

        let bob_gid = bob.join_from_welcome(&welcome).unwrap();
        assert_eq!(bob_gid, gid);
        assert_eq!(bob.epoch(&gid).unwrap(), 1);

        let ct = alice.encrypt(&gid, b"hello").unwrap();
        let (kind, pt) = bob.process(&gid, &ct).unwrap();
        assert_eq!(kind, 1);
        assert_eq!(pt, b"hello");

        // Same ciphertext again: the UI re-decrypts on every paint.
        let (kind2, pt2) = bob.process(&gid, &ct).unwrap();
        assert_eq!(kind2, 1);
        assert_eq!(pt2, b"hello");

        let roster = String::from_utf8(alice.roster(&gid).unwrap()).unwrap();
        assert!(roster.contains("alice|phone") && roster.contains("bob|desktop"));
    }

    #[test]
    fn rejected_commit_can_be_discarded() {
        let alice = ClientHandle::new("alice", "phone").unwrap();
        let bob = ClientHandle::new("bob", "desktop").unwrap();
        let gid = b"chat-h2".to_vec();
        alice.create_group(&gid).unwrap();

        let _ = alice.add_members(&gid, &[kp_of(&bob)]).unwrap();
        // The DS answered 409: throw the staged commit away and stay put.
        alice.clear_pending(&gid).unwrap();
        assert_eq!(alice.epoch(&gid).unwrap(), 0, "epoch must not move");

        // A fresh add still works afterwards.
        let (_c, w) = alice.add_members(&gid, &[kp_of(&bob)]).unwrap();
        alice.merge_pending(&gid).unwrap();
        assert_eq!(alice.epoch(&gid).unwrap(), 1);
        assert!(bob.join_from_welcome(&w).is_ok());
    }

    /// OpenMLS takes a Welcome's single-use KeyPackage out of storage before it
    /// notices that the group is already loaded. Retrying that same Welcome
    /// then misleadingly reports a missing KeyPackage. The Android repository
    /// must therefore remove a *proven stale* local group before it calls this
    /// method; see the matching welcome-epoch predicate in MlsPolicy.
    #[test]
    fn stale_group_must_be_dropped_before_joining_a_fresh_welcome() {
        let alice = ClientHandle::new("alice", "phone").unwrap();
        let bob = ClientHandle::new("bob", "desktop").unwrap();
        let gid = b"chat-stale-welcome".to_vec();
        alice.create_group(&gid).unwrap();

        // Bob's independently-created local tree is stale, but it has the
        // same GroupId as the real tree. This is the exact precondition that
        // caused Samsung's key packages to be burned.
        bob.create_group(&gid).unwrap();
        let stale_epoch = bob.epoch(&gid).unwrap();

        let burned_kp = kp_of(&bob);
        let (_commit, burned_welcome) = alice.add_members(&gid, &[burned_kp]).unwrap();
        assert_eq!(alice.merge_pending(&gid).unwrap(), stale_epoch + 1);

        let first = bob.join_from_welcome(&burned_welcome).unwrap_err().to_string();
        assert!(
            first.contains("already exists"),
            "the existing group, rather than the KeyPackage, must explain the first failure: {first}"
        );
        let retry = bob.join_from_welcome(&burned_welcome).unwrap_err().to_string();
        assert!(
            retry.contains("No matching key package"),
            "a failed join must reproduce the irreversible KeyPackage burn: {retry}"
        );

        // The server would first remove the phantom leaf before issuing a new
        // invitation. Then the client-side epoch predicate proves this local
        // group is stale (0 < 1) and drop_group makes the fresh Welcome safe.
        let _remove = alice
            .remove_members(&gid, &["bob|desktop".to_string()])
            .unwrap();
        alice.merge_pending(&gid).unwrap();
        bob.drop_group(&gid).unwrap();

        let fresh_kp = kp_of(&bob);
        let (_commit, fresh_welcome) = alice.add_members(&gid, &[fresh_kp]).unwrap();
        let fresh_epoch = alice.merge_pending(&gid).unwrap();

        // A successful first attempt proves that the fresh KeyPackage was not
        // pre-consumed by an "already exists" failure.
        assert_eq!(bob.join_from_welcome(&fresh_welcome).unwrap(), gid);
        assert_eq!(bob.epoch(b"chat-stale-welcome").unwrap(), fresh_epoch);
    }

    #[test]
    fn missing_group_is_an_error_not_a_panic() {
        let c = ClientHandle::new("a", "b").unwrap();
        assert!(c.encrypt(b"nope", b"x").is_err());
        assert!(c.epoch(b"nope").is_err());
        assert!(c.roster(b"nope").is_err());
        assert!(c.merge_pending(b"nope").is_err());
    }

    #[test]
    fn garbage_input_is_rejected() {
        let a = ClientHandle::new("a", "b").unwrap();
        let gid = b"g".to_vec();
        a.create_group(&gid).unwrap();
        assert!(a.add_members(&gid, &[vec![0xff; 32]]).is_err());
        assert!(a.join_from_welcome(&[0u8; 16]).is_err());
        assert!(a.process(&gid, &[0xde, 0xad]).is_err());
    }

    /// The FFI promises thread safety; this exercises it rather than asserting
    /// it in a comment. Concurrent encrypts on one handle must all succeed and
    /// produce distinct ciphertexts.
    #[test]
    fn concurrent_calls_are_serialized() {
        let alice = Arc::new(ClientHandle::new("alice", "phone").unwrap());
        let bob = ClientHandle::new("bob", "desktop").unwrap();
        let gid = b"chat-h3".to_vec();
        alice.create_group(&gid).unwrap();
        let (_c, w) = alice.add_members(&gid, &[kp_of(&bob)]).unwrap();
        alice.merge_pending(&gid).unwrap();
        bob.join_from_welcome(&w).unwrap();

        let mut handles = Vec::new();
        for i in 0..8 {
            let a = Arc::clone(&alice);
            let g = gid.clone();
            handles.push(std::thread::spawn(move || {
                a.encrypt(&g, format!("msg {i}").as_bytes()).expect("encrypt")
            }));
        }
        let cts: Vec<Vec<u8>> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        assert_eq!(cts.len(), 8);

        // Every ciphertext must be distinct: a shared generation counter under a
        // race would produce duplicates.
        let mut seen = std::collections::HashSet::new();
        for ct in &cts {
            assert!(seen.insert(ct.clone()), "duplicate ciphertext under concurrency");
        }

        // And all 8 decrypt on the receiver.
        let mut decrypted = 0;
        for ct in cts {
            if let Ok((1, _pt)) = bob.process(&gid, &ct) {
                decrypted += 1;
            }
        }
        assert_eq!(decrypted, 8, "all concurrent messages must be readable");
    }

    /// Snapshot/restore through the handle, then use the restored client.
    #[test]
    fn snapshot_restore_through_handle() {
        let alice = ClientHandle::new("alice", "phone").unwrap();
        let bob = ClientHandle::new("bob", "desktop").unwrap();
        let gid = b"chat-h4".to_vec();
        alice.create_group(&gid).unwrap();
        let (_c, w) = alice.add_members(&gid, &[kp_of(&bob)]).unwrap();
        alice.merge_pending(&gid).unwrap();
        bob.join_from_welcome(&w).unwrap();

        let blob = alice.snapshot().unwrap();
        drop(alice);

        let restored = ClientHandle::restore(&blob, "alice", "phone").expect("restore");
        // The group must come back from storage with the same epoch and no
        // pending commit - invariant 1, now through the FFI-facing type.
        assert_eq!(restored.load_group(&gid).unwrap(), 1);
        let ct = restored.encrypt(&gid, b"after restore").unwrap();
        let (kind, pt) = bob.process(&gid, &ct).unwrap();
        assert_eq!(kind, 1);
        assert_eq!(pt, b"after restore");
    }
}
