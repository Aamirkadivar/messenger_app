//! MLS group operations. The ONLY place in this project where MLS
//! cryptography lives — Kotlin and C++ must never reimplement any of it.
//!
//! Everything here is exercised by in-process tests at the bottom of the file.
//! That is deliberate: the previous design's worst bug (an Add-only commit
//! omitting its UpdatePath, which the other implementation rejected) was only
//! ever reproducible on two physical devices, one 15-minute rebuild apart. With
//! one core, that class of bug is a unit test.

use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;

/// RFC 9420 mandatory-to-implement suite. Both clients must agree; changing it
/// is a protocol migration, not a config tweak.
pub const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

#[derive(Debug)]
pub enum MlsError {
    Crypto(String),
    Group(String),
    NotFound(String),
}

impl std::fmt::Display for MlsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MlsError::Crypto(m) => write!(f, "crypto: {m}"),
            MlsError::Group(m) => write!(f, "group: {m}"),
            MlsError::NotFound(m) => write!(f, "not found: {m}"),
        }
    }
}

type Result<T> = std::result::Result<T, MlsError>;

/// One device's MLS identity. The credential is `"user_id|device_id"`: every
/// device is its own leaf, and the ratchet tree must be able to tell two devices
/// of one account apart. (v1 credentialed by user only, which made the roster
/// useless for membership checks and silently excluded second devices.)
pub struct Identity {
    pub credential: CredentialWithKey,
    pub signer: SignatureKeyPair,
}

pub fn credential_string(user_id: &str, device_id: &str) -> String {
    if device_id.is_empty() {
        user_id.to_string()
    } else {
        format!("{user_id}|{device_id}")
    }
}

/// A device-local MLS client: provider (crypto + rand + storage) plus identity.
pub struct MlsClient {
    pub provider: OpenMlsRustCrypto,
    pub identity: Identity,
}

/// Sender-ratchet tolerance, shared by create and join so both sides agree.
///
/// The default out-of-order window is small (5). Eight concurrent sends from one
/// device already exceeded it in testing: messages arrive in a different order
/// than they were generated and the receiver rejects the stragglers. Our DS fans
/// out over a WebSocket, so reordering is normal, not exceptional.
///
/// Trade-off: a wider window keeps more unused message keys alive, which is a
/// bounded weakening of forward secrecy. 32 covers realistic reordering.
fn sender_ratchet() -> SenderRatchetConfiguration {
    SenderRatchetConfiguration::new(32, 1000)
}

/// Keep a small window of past-epoch secrets. Our DS fans out over a WebSocket
/// while commits land concurrently, so an application message from epoch N can
/// arrive after we reach N+1. Without this it fails outright. Trade-off:
/// slightly weaker forward secrecy, bounded to `max_past_epochs` epochs.
const MAX_PAST_EPOCHS: usize = 3;

/// Join-side config. MUST include the ratchet-tree extension: the Delivery
/// Service does not serve a tree alongside a Welcome, and a member who later
/// adds someone must put the tree in that Welcome too. Default is off, which
/// left second-hop joins unable to open the group.
fn join_config() -> MlsGroupJoinConfig {
    MlsGroupJoinConfig::builder()
        .max_past_epochs(MAX_PAST_EPOCHS)
        .use_ratchet_tree_extension(true)
        .sender_ratchet_configuration(sender_ratchet())
        .build()
}

fn create_config() -> MlsGroupCreateConfig {
    MlsGroupCreateConfig::builder()
        .ciphersuite(CIPHERSUITE)
        .use_ratchet_tree_extension(true)
        .max_past_epochs(MAX_PAST_EPOCHS)
        .sender_ratchet_configuration(sender_ratchet())
        .build()
}

impl MlsClient {
    pub fn new(user_id: &str, device_id: &str) -> Result<Self> {
        let provider = OpenMlsRustCrypto::default();
        let id = credential_string(user_id, device_id);

        let credential = BasicCredential::new(id.into_bytes());
        let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm())
            .map_err(|e| MlsError::Crypto(format!("signature keypair: {e}")))?;

        // The signature private key lives in the provider's storage, never in a
        // buffer we hand across the FFI.
        signer
            .store(provider.storage())
            .map_err(|e| MlsError::Crypto(format!("store signer: {e}")))?;

        let credential = CredentialWithKey {
            credential: credential.into(),
            signature_key: signer.public().into(),
        };

        Ok(Self {
            provider,
            identity: Identity { credential, signer },
        })
    }

    /// Produces a KeyPackage others use to add this device. Single-use: the
    /// private init key stays in local storage, so the same KeyPackage must
    /// never be claimed twice.
    pub fn new_key_package(&self) -> Result<KeyPackageBundle> {
        KeyPackage::builder()
            .build(
                CIPHERSUITE,
                &self.provider,
                &self.identity.signer,
                self.identity.credential.clone(),
            )
            .map_err(|e| MlsError::Crypto(format!("key package: {e}")))
    }

    pub fn create_group(&self, group_id: &[u8]) -> Result<MlsGroup> {
        let cfg = create_config();

        MlsGroup::new_with_group_id(
            &self.provider,
            &self.identity.signer,
            &cfg,
            GroupId::from_slice(group_id),
            self.identity.credential.clone(),
        )
        .map_err(|e| MlsError::Group(format!("create: {e}")))
    }

    /// Adds devices and returns `(commit, welcome)`.
    ///
    /// The commit is **staged, not merged**: the caller merges only once the
    /// Delivery Service has accepted it at the expected epoch. Merging first
    /// meant local state could advance past a commit the server rejected —
    /// exactly how v1 forked its groups.
    pub fn add_members(
        &self,
        group: &mut MlsGroup,
        key_packages: &[KeyPackage],
    ) -> Result<(MlsMessageOut, MlsMessageOut)> {
        let (commit, welcome, _group_info) = group
            .add_members(&self.provider, &self.identity.signer, key_packages)
            .map_err(|e| MlsError::Group(format!("add_members: {e}")))?;
        Ok((commit, welcome))
    }

    pub fn remove_members(
        &self,
        group: &mut MlsGroup,
        leaves: &[LeafNodeIndex],
    ) -> Result<MlsMessageOut> {
        let (commit, _welcome, _gi) = group
            .remove_members(&self.provider, &self.identity.signer, leaves)
            .map_err(|e| MlsError::Group(format!("remove_members: {e}")))?;
        Ok(commit)
    }

    /// Rotates this device's leaf key — the post-compromise security step.
    pub fn self_update(&self, group: &mut MlsGroup) -> Result<MlsMessageOut> {
        let bundle = group
            .self_update(&self.provider, &self.identity.signer, LeafNodeParameters::default())
            .map_err(|e| MlsError::Group(format!("self_update: {e}")))?;
        Ok(bundle.into_messages().0)
    }

    pub fn merge_pending(&self, group: &mut MlsGroup) -> Result<()> {
        group
            .merge_pending_commit(&self.provider)
            .map_err(|e| MlsError::Group(format!("merge_pending: {e}")))
    }

    pub fn join_from_welcome(&self, welcome: MlsMessageIn) -> Result<MlsGroup> {
        let welcome = match welcome.extract() {
            MlsMessageBodyIn::Welcome(w) => w,
            _ => return Err(MlsError::Group("not a Welcome".into())),
        };

        let cfg = join_config();

        let staged = StagedWelcome::new_from_welcome(&self.provider, &cfg, welcome, None)
            .map_err(|e| MlsError::Group(format!("staged welcome: {e}")))?;

        staged
            .into_group(&self.provider)
            .map_err(|e| MlsError::Group(format!("into_group: {e}")))
    }

    /// Encrypts an application message.
    ///
    /// Note: OpenMLS discards the key immediately for forward secrecy, so the
    /// SENDER CANNOT DECRYPT THIS. Callers must persist their own plaintext —
    /// it is the only readable copy that will ever exist.
    pub fn encrypt(&self, group: &mut MlsGroup, plaintext: &[u8]) -> Result<MlsMessageOut> {
        group
            .create_message(&self.provider, &self.identity.signer, plaintext)
            .map_err(|e| MlsError::Group(format!("create_message: {e}")))
    }

    /// Processes any incoming MLS message: application, proposal or commit.
    pub fn process(&self, group: &mut MlsGroup, msg: MlsMessageIn) -> Result<Processed> {
        let protocol = msg
            .try_into_protocol_message()
            .map_err(|e| MlsError::Group(format!("not a protocol message: {e}")))?;

        let processed = group
            .process_message(&self.provider, protocol)
            .map_err(|e| MlsError::Group(format!("process_message: {e}")))?;

        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => {
                Ok(Processed::Application(app.into_bytes()))
            }
            ProcessedMessageContent::StagedCommitMessage(staged) => {
                group
                    .merge_staged_commit(&self.provider, *staged)
                    .map_err(|e| MlsError::Group(format!("merge_staged_commit: {e}")))?;
                Ok(Processed::Commit {
                    new_epoch: group.epoch().as_u64(),
                })
            }
            ProcessedMessageContent::ProposalMessage(_) => Ok(Processed::Proposal),
            ProcessedMessageContent::ExternalJoinProposalMessage(_) => Ok(Processed::Proposal),
        }
    }

    /// Credentials of every current leaf. The tree is the only trustworthy
    /// answer to "is this device already a member?" — v1 trusted a locally
    /// persisted "invited" set that outlived server resets and deadlocked.
    /// Signature public keys of the current members.
    ///
    /// MLS forbids two members sharing a signature key, so an Add proposing one
    /// that is already in the tree is rejected - taking the whole batch with it.
    /// A caller building an Add needs to know what is already there.
    pub fn member_signature_keys(&self, group: &MlsGroup) -> Vec<Vec<u8>> {
        group.members().map(|m| m.signature_key.to_vec()).collect()
    }

    pub fn roster(&self, group: &MlsGroup) -> Vec<String> {
        group
            .members()
            .map(|m| String::from_utf8_lossy(m.credential.serialized_content()).into_owned())
            .collect()
    }
}

pub enum Processed {
    Application(Vec<u8>),
    Commit { new_epoch: u64 },
    Proposal,
}

// ------------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;
    use openmls::prelude::tls_codec::{Deserialize, Serialize};

    /// Round-trips a message through bytes, the way the real transport does.
    /// Testing with in-memory objects would hide wire-format bugs — which is
    /// precisely the class that broke v1.
    fn wire(msg: MlsMessageOut) -> MlsMessageIn {
        let bytes = msg.tls_serialize_detached().expect("serialize");
        MlsMessageIn::tls_deserialize(&mut bytes.as_slice()).expect("deserialize")
    }

    fn client(user: &str, device: &str) -> MlsClient {
        MlsClient::new(user, device).expect("client")
    }

    #[test]
    fn credential_is_device_scoped() {
        assert_eq!(credential_string("u1", "d1"), "u1|d1");
        assert_eq!(credential_string("u1", ""), "u1");
    }

    /// The headline test: two independent clients, a real Welcome over the wire,
    /// and a message decrypted by the receiver.
    #[test]
    fn two_clients_exchange_a_message() {
        let alice = client("alice", "phone");
        let bob = client("bob", "desktop");

        let mut a_group = alice.create_group(b"chat-1").expect("create");
        assert_eq!(a_group.epoch().as_u64(), 0);

        let bob_kp = bob.new_key_package().expect("kp");
        let (_commit, welcome) = alice
            .add_members(&mut a_group, &[bob_kp.key_package().clone()])
            .expect("add");
        alice.merge_pending(&mut a_group).expect("merge");
        assert_eq!(a_group.epoch().as_u64(), 1);

        let mut b_group = bob.join_from_welcome(wire(welcome)).expect("join");
        assert_eq!(b_group.epoch().as_u64(), 1, "joiner starts at the add epoch");

        // Both see both devices, by credential.
        let mut roster = alice.roster(&a_group);
        roster.sort();
        assert_eq!(roster, vec!["alice|phone", "bob|desktop"]);
        assert_eq!(alice.roster(&a_group).len(), bob.roster(&b_group).len());

        // Alice -> Bob
        let ct = alice.encrypt(&mut a_group, b"hello bob").expect("encrypt");
        match bob.process(&mut b_group, wire(ct)).expect("process") {
            Processed::Application(pt) => assert_eq!(pt, b"hello bob"),
            _ => panic!("expected an application message"),
        }

        // Bob -> Alice
        let ct = bob.encrypt(&mut b_group, b"hi alice").expect("encrypt");
        match alice.process(&mut a_group, wire(ct)).expect("process") {
            Processed::Application(pt) => assert_eq!(pt, b"hi alice"),
            _ => panic!("expected an application message"),
        }
    }

    /// Documents the constraint every caller must design around: the sender
    /// cannot read its own message back, so the app must keep the plaintext.
    #[test]
    fn sender_cannot_decrypt_its_own_message() {
        let alice = client("alice", "phone");
        let bob = client("bob", "desktop");
        let mut a_group = alice.create_group(b"chat-2").unwrap();
        let kp = bob.new_key_package().unwrap();
        let (_c, w) = alice.add_members(&mut a_group, &[kp.key_package().clone()]).unwrap();
        alice.merge_pending(&mut a_group).unwrap();
        let _b_group = bob.join_from_welcome(wire(w)).unwrap();

        let ct = alice.encrypt(&mut a_group, b"secret").unwrap();
        assert!(
            alice.process(&mut a_group, wire(ct)).is_err(),
            "OpenMLS drops the key at encrypt time for forward secrecy"
        );
    }

    /// Three devices, two of them the same user — the multi-device shape v1
    /// never achieved, where a second device of one account was excluded.
    #[test]
    fn multi_device_same_account() {
        let a_phone = client("alice", "phone");
        let a_desktop = client("alice", "desktop");
        let bob = client("bob", "phone");

        let mut g = a_phone.create_group(b"chat-3").unwrap();
        let kps = [
            a_desktop.new_key_package().unwrap().key_package().clone(),
            bob.new_key_package().unwrap().key_package().clone(),
        ];
        let (_c, w) = a_phone.add_members(&mut g, &kps).unwrap();
        a_phone.merge_pending(&mut g).unwrap();

        let mut roster = a_phone.roster(&g);
        roster.sort();
        assert_eq!(roster, vec!["alice|desktop", "alice|phone", "bob|phone"]);

        // Both joiners open the SAME Welcome; each finds its own secrets.
        let mut d_group = a_desktop.join_from_welcome(wire(w.clone())).unwrap();
        let mut b_group = bob.join_from_welcome(wire(w)).unwrap();

        // Alice's phone sends; her desktop reads it. This is the exact case the
        // user reported as broken for an entire session.
        let ct = a_phone.encrypt(&mut g, b"from my phone").unwrap();
        match a_desktop.process(&mut d_group, wire(ct.clone())).unwrap() {
            Processed::Application(pt) => assert_eq!(pt, b"from my phone"),
            _ => panic!("desktop must read the phone's message"),
        }
        match bob.process(&mut b_group, wire(ct)).unwrap() {
            Processed::Application(pt) => assert_eq!(pt, b"from my phone"),
            _ => panic!("bob must read it too"),
        }
    }

    /// A commit must apply cleanly on the other side. v1 failed here with
    /// "Path required but not present" because two implementations disagreed
    /// about whether an Add-only commit needs an UpdatePath.
    #[test]
    fn commit_applies_across_clients() {
        let alice = client("alice", "phone");
        let bob = client("bob", "desktop");
        let carol = client("carol", "phone");

        let mut a = alice.create_group(b"chat-4").unwrap();
        let (_c, w) = alice
            .add_members(&mut a, &[bob.new_key_package().unwrap().key_package().clone()])
            .unwrap();
        alice.merge_pending(&mut a).unwrap();
        let mut b = bob.join_from_welcome(wire(w)).unwrap();

        // Now add Carol; Bob must be able to apply Alice's commit.
        let (commit, _w2) = alice
            .add_members(&mut a, &[carol.new_key_package().unwrap().key_package().clone()])
            .unwrap();
        alice.merge_pending(&mut a).unwrap();

        match bob.process(&mut b, wire(commit)).unwrap() {
            Processed::Commit { new_epoch } => assert_eq!(new_epoch, 2),
            _ => panic!("expected a commit"),
        }
        assert_eq!(a.epoch(), b.epoch(), "epochs must converge");
    }

    /// A staged commit that the DS rejects must not advance local state.
    #[test]
    fn unmerged_commit_does_not_advance_epoch() {
        let alice = client("alice", "phone");
        let bob = client("bob", "desktop");
        let mut g = alice.create_group(b"chat-5").unwrap();

        let before = g.epoch().as_u64();
        let _ = alice
            .add_members(&mut g, &[bob.new_key_package().unwrap().key_package().clone()])
            .unwrap();
        assert_eq!(g.epoch().as_u64(), before, "staged commit must not advance");

        alice.merge_pending(&mut g).unwrap();
        assert_eq!(g.epoch().as_u64(), before + 1);
    }

    /// Removal must take effect and the removed device must lose access
    /// (forward secrecy).
    #[test]
    fn removed_member_is_gone_from_the_roster() {
        let alice = client("alice", "phone");
        let bob = client("bob", "desktop");
        let mut a = alice.create_group(b"chat-6").unwrap();
        let (_c, w) = alice
            .add_members(&mut a, &[bob.new_key_package().unwrap().key_package().clone()])
            .unwrap();
        alice.merge_pending(&mut a).unwrap();
        let mut b = bob.join_from_welcome(wire(w)).unwrap();

        let bob_leaf = a
            .members()
            .find(|m| m.credential.serialized_content() == b"bob|desktop")
            .map(|m| m.index)
            .expect("bob is a member");

        let commit = alice.remove_members(&mut a, &[bob_leaf]).unwrap();
        alice.merge_pending(&mut a).unwrap();
        assert_eq!(alice.roster(&a), vec!["alice|phone"]);

        // Bob applies his own removal and can no longer read new messages.
        let _ = bob.process(&mut b, wire(commit));
        let ct = alice.encrypt(&mut a, b"after removal").unwrap();
        assert!(
            bob.process(&mut b, wire(ct)).is_err(),
            "a removed device must not decrypt later messages"
        );
    }

    /// Key rotation for post-compromise security must keep the group readable.
    #[test]
    fn self_update_rotates_and_group_survives() {
        let alice = client("alice", "phone");
        let bob = client("bob", "desktop");
        let mut a = alice.create_group(b"chat-7").unwrap();
        let (_c, w) = alice
            .add_members(&mut a, &[bob.new_key_package().unwrap().key_package().clone()])
            .unwrap();
        alice.merge_pending(&mut a).unwrap();
        let mut b = bob.join_from_welcome(wire(w)).unwrap();

        let commit = bob.self_update(&mut b).unwrap();
        bob.merge_pending(&mut b).unwrap();
        match alice.process(&mut a, wire(commit)).unwrap() {
            Processed::Commit { new_epoch } => assert_eq!(new_epoch, 2),
            _ => panic!("expected a commit"),
        }

        let ct = bob.encrypt(&mut b, b"after rotation").unwrap();
        match alice.process(&mut a, wire(ct)).unwrap() {
            Processed::Application(pt) => assert_eq!(pt, b"after rotation"),
            _ => panic!("expected application message"),
        }
    }
}
