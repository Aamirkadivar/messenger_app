//! Delivery Service contract tests: two real MLS clients driven through a
//! faithful model of the Go DS.
//!
//! Why a model rather than HTTP: the behaviours that forked v1's groups were all
//! *protocol* behaviours — epoch fencing, single-use KeyPackages, client-acked
//! Welcomes, replay of missed handshakes. Those are exactly what a model can
//! enforce, and enforcing them here means a bug fails in 80ms instead of after
//! two APK installs. The HTTP wiring is separately checked against
//! back-end/handlers/mls.go, whose rules this file mirrors:
//!
//!   * `SubmitCommit`  — accepts only `expected_epoch == current`, else 409
//!   * `ClaimKeyPackage` — single-use, `FOR UPDATE SKIP LOCKED`
//!   * `GetWelcomes`   — hands out, does NOT consume
//!   * `AckWelcome`    — consumes, only after the client joined
//!   * `GetHandshakes` — ordered, `since_epoch`
//!   * `GetCoverage`   — newest Welcome per device wins, ordered by created_at
//!
//! If those rules change on the server, these tests must change with them.

use std::collections::HashMap;

use mls_core::client::ClientHandle;

// ---------------------------------------------------------------- fake DS

#[derive(Debug, PartialEq)]
enum SubmitResult {
    Accepted { new_epoch: u64 },
    /// The DS rejected a stale commit and reports where the group actually is.
    Conflict { current_epoch: u64 },
}

struct Welcome {
    id: u64,
    device: String,
    blob: Vec<u8>,
    consumed: bool,
    /// Group epoch the adding commit produced — mirrors `MLSWelcome.Epoch`,
    /// which the Go handler writes inside the same transaction that advances
    /// the group.
    epoch: u64,
    /// Monotonic stand-in for `MLSWelcome.CreatedAt`. Deliberately separate
    /// from both `id` and `epoch`: RecreateGroup zeroes the group epoch while
    /// keeping consumed rows, so only wall-clock order survives a recreation.
    created_at: u64,
}

/// What the DS can say about join progress without reading the ratchet tree.
///
/// `acked` is "joined through some Welcome, ever". `pending` is "this device's
/// NEWEST Welcome is unconsumed AND the group has already moved past it" — an
/// invitation that is outstanding rather than merely in flight. A device can be
/// in both: joined once, removed, re-invited, and not back yet.
struct Coverage {
    acked: Vec<String>,
    pending: Vec<String>,
}

/// Models the Go DS. Holds only opaque bytes plus integers — mirroring the
/// server-blindness claim in docs/mls-v2-architecture.md §9.
struct FakeDs {
    epoch: u64,
    /// epoch -> commit blob, in order.
    handshakes: Vec<(u64, Vec<u8>)>,
    /// device -> queue of unclaimed key packages (single-use).
    key_packages: HashMap<String, Vec<Vec<u8>>>,
    welcomes: Vec<Welcome>,
    next_welcome_id: u64,
    /// Ticks once per queued Welcome, standing in for the server clock.
    clock: u64,
    /// Application messages, in submission order.
    messages: Vec<Vec<u8>>,
}

impl FakeDs {
    fn new() -> Self {
        Self {
            epoch: 0,
            handshakes: Vec::new(),
            key_packages: HashMap::new(),
            welcomes: Vec::new(),
            next_welcome_id: 1,
            clock: 1,
            messages: Vec::new(),
        }
    }

    fn publish_key_packages(&mut self, device: &str, kps: Vec<Vec<u8>>) {
        self.key_packages.entry(device.to_string()).or_default().extend(kps);
    }

    fn count_key_packages(&self, device: &str) -> usize {
        // Per DEVICE, never per account: counting per user let a new device see
        // a sibling's stock, publish nothing, and become unaddable.
        self.key_packages.get(device).map_or(0, |v| v.len())
    }

    /// Single-use. A second claim for the same device must fail.
    fn claim_key_package(&mut self, device: &str) -> Option<Vec<u8>> {
        self.key_packages.get_mut(device)?.pop()
    }

    fn submit_commit(&mut self, expected_epoch: u64, blob: Vec<u8>) -> SubmitResult {
        if expected_epoch != self.epoch {
            return SubmitResult::Conflict { current_epoch: self.epoch };
        }
        self.epoch += 1;
        self.handshakes.push((self.epoch, blob));
        SubmitResult::Accepted { new_epoch: self.epoch }
    }

    /// Stamps the Welcome with the epoch the adding commit just produced, the
    /// way SubmitCommit does: the row is written in the same transaction that
    /// advanced the group, so `epoch` is always the post-commit value.
    fn queue_welcome(&mut self, device: &str, blob: Vec<u8>) {
        let epoch = self.epoch;
        self.queue_welcome_at(device, blob, epoch, None);
    }

    /// Injects a row at a chosen point in the ledger, for modelling history a
    /// test cannot reach by committing — an invitation from a dead store
    /// incarnation, or one that predates a recreation.
    fn queue_welcome_at(&mut self, device: &str, blob: Vec<u8>, epoch: u64, created_at: Option<u64>) {
        let id = self.next_welcome_id;
        self.next_welcome_id += 1;
        let created_at = created_at.unwrap_or_else(|| {
            let t = self.clock;
            self.clock += 1;
            t
        });
        self.welcomes.push(Welcome {
            id,
            device: device.to_string(),
            blob,
            consumed: false,
            epoch,
            created_at,
        });
    }

    /// Mirrors `GetCoverage` in back-end/handlers/mls.go.
    ///
    /// A device's Welcomes are a chronological invitation ledger and only the
    /// NEWEST row describes where that device stands. Folding with "any
    /// unconsumed row wins" made a device whose ancient Welcome could never be
    /// opened pending forever, and the client's phantom eviction reads this —
    /// so "pending forever" meant "evicted every pass".
    ///
    /// Recency is `created_at`, never `epoch`: a recreation zeroes the group
    /// epoch while keeping consumed rows.
    fn coverage(&self) -> Coverage {
        let mut ordered: Vec<&Welcome> = self.welcomes.iter().collect();
        ordered.sort_by_key(|w| (w.created_at, w.epoch));

        let mut acked: Vec<String> = Vec::new();
        let mut newest: HashMap<&str, &Welcome> = HashMap::new();
        let mut devices: Vec<&str> = Vec::new();

        for w in ordered {
            if w.consumed && !acked.iter().any(|d| d == &w.device) {
                acked.push(w.device.clone());
            }
            match newest.get(w.device.as_str()) {
                None => {
                    devices.push(w.device.as_str());
                    newest.insert(w.device.as_str(), w);
                }
                Some(prev) => {
                    if (w.created_at, w.epoch) > (prev.created_at, prev.epoch) {
                        newest.insert(w.device.as_str(), w);
                    }
                }
            }
        }

        let mut pending = Vec::new();
        for d in devices {
            let w = newest[d];
            // Consumed newest: joined, nothing outstanding. Unconsumed but at
            // the current epoch: issued by the commit that just landed, and the
            // invitee has not had a chance to poll for it yet.
            if !w.consumed && w.epoch < self.epoch {
                pending.push(d.to_string());
            }
        }
        Coverage { acked, pending }
    }

    /// Hands out pending Welcomes WITHOUT consuming them. v1 consumed on
    /// handout, so one failed join locked the device out of the group forever.
    fn get_welcomes(&self, device: &str) -> Vec<(u64, Vec<u8>)> {
        self.welcomes
            .iter()
            .filter(|w| w.device == device && !w.consumed)
            .map(|w| (w.id, w.blob.clone()))
            .collect()
    }

    fn ack_welcome(&mut self, id: u64) {
        if let Some(w) = self.welcomes.iter_mut().find(|w| w.id == id) {
            w.consumed = true;
        }
    }

    fn handshakes_since(&self, since_epoch: u64) -> Vec<Vec<u8>> {
        self.handshakes
            .iter()
            .filter(|(e, _)| *e > since_epoch)
            .map(|(_, b)| b.clone())
            .collect()
    }
}

// ------------------------------------------------------------------ helpers

/// One device: an MLS client plus the id the DS knows it by.
struct Device {
    handle: ClientHandle,
    id: String,
}

impl Device {
    fn new(user: &str, device: &str) -> Self {
        Self {
            handle: ClientHandle::new(user, device).expect("client"),
            id: format!("{user}|{device}"),
        }
    }

    /// Tops up published KeyPackages the way a real client does.
    fn publish(&self, ds: &mut FakeDs, n: usize) {
        let kps: Vec<Vec<u8>> = (0..n).map(|_| self.handle.key_package().expect("kp")).collect();
        ds.publish_key_packages(&self.id, kps);
    }
}

/// Adds `joiner` to `gid` on behalf of `adder`, obeying the epoch fence and
/// retrying once on conflict — the exact loop a real client must implement.
fn add_member(
    ds: &mut FakeDs,
    adder: &Device,
    joiner: &Device,
    gid: &[u8],
) -> Result<(), String> {
    let kp = ds
        .claim_key_package(&joiner.id)
        .ok_or_else(|| format!("no key package for {}", joiner.id))?;

    for _attempt in 0..2 {
        let expected = adder.handle.epoch(gid).map_err(|e| e.to_string())?;
        let (commit, welcome) = adder
            .handle
            .add_members(gid, &[kp.clone()])
            .map_err(|e| e.to_string())?;

        match ds.submit_commit(expected, commit) {
            SubmitResult::Accepted { .. } => {
                adder.handle.merge_pending(gid).map_err(|e| e.to_string())?;
                ds.queue_welcome(&joiner.id, welcome);
                return Ok(());
            }
            SubmitResult::Conflict { .. } => {
                // Throw the staged commit away, catch up, and rebuild it. v1
                // kept the stale commit and forked the group.
                adder.handle.clear_pending(gid).map_err(|e| e.to_string())?;
                let since = adder.handle.epoch(gid).map_err(|e| e.to_string())?;
                for hs in ds.handshakes_since(since) {
                    let _ = adder.handle.process(gid, &hs);
                }
            }
        }
    }
    Err("could not commit after retry".into())
}

/// Replays any commits a device has not seen. This models what the client MUST
/// do when the DS pushes `group.commit`: v1 had no such handler on Windows, so a
/// member silently fell behind and every later message failed to decrypt with
/// "Message epoch differs from the group's epoch".
fn catch_up(ds: &FakeDs, dev: &Device, gid: &[u8]) {
    let since = match dev.handle.epoch(gid) {
        Ok(e) => e,
        Err(_) => return, // not a member yet
    };
    for hs in ds.handshakes_since(since) {
        let _ = dev.handle.process(gid, &hs);
    }
}

/// Fetches, joins, and only then acks — the ordering that makes a failed join
/// recoverable.
fn join_pending(ds: &mut FakeDs, dev: &Device) -> Vec<Vec<u8>> {
    let mut joined = Vec::new();
    for (id, blob) in ds.get_welcomes(&dev.id) {
        match dev.handle.join_from_welcome(&blob) {
            Ok(gid) => {
                ds.ack_welcome(id);
                joined.push(gid);
            }
            Err(_) => { /* leave it pending for the next attempt */ }
        }
    }
    joined
}

/// The client half of phantom detection: DS evidence INTERSECTED with the local
/// ratchet tree.
///
/// Neither half is sufficient alone. The DS never parses a commit, so it cannot
/// see removals and cannot tell "joined and still a member" from "joined and
/// later removed" — only the tree knows that. The DS holds the invitation
/// ledger, which the tree knows nothing about. A phantom is precisely the
/// disagreement: a leaf that is present, for a device that has not joined.
fn phantoms(ds: &FakeDs, adder: &Device, gid: &[u8]) -> Vec<String> {
    let pending = ds.coverage().pending;
    let roster = adder
        .handle
        .roster(gid)
        .ok()
        .and_then(|r| String::from_utf8(r).ok())
        .unwrap_or_default();
    roster
        .lines()
        .filter(|cred| !cred.is_empty() && *cred != adder.id)
        .filter(|cred| pending.iter().any(|p| p == cred))
        .map(|c| c.to_string())
        .collect()
}

/// One pass of the client's `inviteMissingDevices`: evict proven phantoms, then
/// add every chat device that is not already a leaf.
fn invite_pass(ds: &mut FakeDs, adder: &Device, gid: &[u8], chat_devices: &[&Device]) {
    for cred in phantoms(ds, adder, gid) {
        let expected = match adder.handle.epoch(gid) {
            Ok(e) => e,
            Err(_) => return,
        };
        let commit = match adder.handle.remove_members(gid, &[cred.clone()]) {
            Ok(c) => c,
            // Removing nobody is an error, never an empty commit.
            Err(_) => continue,
        };
        match ds.submit_commit(expected, commit) {
            SubmitResult::Accepted { .. } => {
                adder.handle.merge_pending(gid).expect("merge removal");
            }
            SubmitResult::Conflict { .. } => {
                adder.handle.clear_pending(gid).expect("discard removal");
            }
        }
    }

    let roster = adder
        .handle
        .roster(gid)
        .ok()
        .and_then(|r| String::from_utf8(r).ok())
        .unwrap_or_default();
    let current: Vec<&str> = roster.lines().collect();
    for d in chat_devices {
        if d.id == adder.id || current.contains(&d.id.as_str()) {
            continue;
        }
        let _ = add_member(ds, adder, d, gid);
    }
}

// -------------------------------------------------------------------- tests

#[test]
fn full_flow_two_devices() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "phone");
    let bob = Device::new("bob", "desktop");
    let gid = b"chat-1".to_vec();

    alice.publish(&mut ds, 5);
    bob.publish(&mut ds, 5);
    assert_eq!(ds.count_key_packages(&bob.id), 5);

    alice.handle.create_group(&gid).unwrap();
    add_member(&mut ds, &alice, &bob, &gid).expect("add bob");

    // Claiming consumed exactly one.
    assert_eq!(ds.count_key_packages(&bob.id), 4);

    let joined = join_pending(&mut ds, &bob);
    assert_eq!(joined, vec![gid.clone()]);
    assert!(ds.get_welcomes(&bob.id).is_empty(), "acked after joining");

    assert_eq!(alice.handle.epoch(&gid).unwrap(), 1);
    assert_eq!(bob.handle.epoch(&gid).unwrap(), 1);

    // Messages both ways, through the DS.
    let ct = alice.handle.encrypt(&gid, b"hi bob").unwrap();
    ds.messages.push(ct);
    let (kind, pt) = bob.handle.process(&gid, &ds.messages[0]).unwrap();
    assert_eq!((kind, pt.as_slice()), (1u8, b"hi bob".as_slice()));

    let ct = bob.handle.encrypt(&gid, b"hi alice").unwrap();
    let (kind, pt) = alice.handle.process(&gid, &ct).unwrap();
    assert_eq!((kind, pt.as_slice()), (1u8, b"hi alice".as_slice()));
}

#[test]
fn key_packages_are_single_use() {
    let mut ds = FakeDs::new();
    let bob = Device::new("bob", "desktop");
    bob.publish(&mut ds, 1);

    assert!(ds.claim_key_package(&bob.id).is_some());
    assert!(
        ds.claim_key_package(&bob.id).is_none(),
        "a KeyPackage must never be claimed twice"
    );
}

#[test]
fn key_package_count_is_per_device_not_per_account() {
    let mut ds = FakeDs::new();
    let phone = Device::new("alice", "phone");
    let desktop = Device::new("alice", "desktop");

    phone.publish(&mut ds, 10);

    // The bug that made multi-device impossible: desktop asked "how many do I
    // have?", was told 10 (the phone's stock), published none, and could never
    // be added to a group.
    assert_eq!(ds.count_key_packages(&desktop.id), 0);
    assert_eq!(ds.count_key_packages(&phone.id), 10);
}

/// A Welcome the client could not open must stay pending. v1 consumed on
/// handout, so a single silent join failure was permanent exclusion.
#[test]
fn unopenable_welcome_stays_pending() {
    let mut ds = FakeDs::new();
    let bob = Device::new("bob", "desktop");

    ds.queue_welcome(&bob.id, vec![0xde, 0xad, 0xbe, 0xef]);
    let joined = join_pending(&mut ds, &bob);
    assert!(joined.is_empty(), "garbage must not join");
    assert_eq!(
        ds.get_welcomes(&bob.id).len(),
        1,
        "a failed join must leave the Welcome claimable again"
    );
}

/// Two members commit at the same epoch. The DS accepts one and 409s the other;
/// the loser must resync and retry rather than fork the group.
#[test]
fn concurrent_commits_do_not_fork_the_group() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "phone");
    let bob = Device::new("bob", "desktop");
    let carol = Device::new("carol", "phone");
    let dave = Device::new("dave", "tablet");
    let gid = b"chat-2".to_vec();

    for d in [&alice, &bob, &carol, &dave] {
        d.publish(&mut ds, 3);
    }

    alice.handle.create_group(&gid).unwrap();
    add_member(&mut ds, &alice, &bob, &gid).unwrap();
    join_pending(&mut ds, &bob);
    assert_eq!(alice.handle.epoch(&gid).unwrap(), bob.handle.epoch(&gid).unwrap());

    // Both stage a commit at the same epoch.
    let at = alice.handle.epoch(&gid).unwrap();
    let carol_kp = ds.claim_key_package(&carol.id).unwrap();
    let dave_kp = ds.claim_key_package(&dave.id).unwrap();
    let (a_commit, a_welcome) = alice.handle.add_members(&gid, &[carol_kp]).unwrap();
    let (b_commit, _b_welcome) = bob.handle.add_members(&gid, &[dave_kp]).unwrap();

    // Alice wins the race.
    assert_eq!(ds.submit_commit(at, a_commit), SubmitResult::Accepted { new_epoch: at + 1 });
    alice.handle.merge_pending(&gid).unwrap();
    ds.queue_welcome(&carol.id, a_welcome);

    // Bob is fenced out.
    assert_eq!(
        ds.submit_commit(at, b_commit),
        SubmitResult::Conflict { current_epoch: at + 1 },
        "the DS must reject a stale commit"
    );

    // Bob discards his commit, replays Alice's, and converges.
    bob.handle.clear_pending(&gid).unwrap();
    for hs in ds.handshakes_since(bob.handle.epoch(&gid).unwrap()) {
        bob.handle.process(&gid, &hs).unwrap();
    }
    assert_eq!(
        bob.handle.epoch(&gid).unwrap(),
        alice.handle.epoch(&gid).unwrap(),
        "epochs must converge after a conflict"
    );

    // The group still works, and Carol (added by the winning commit) can read.
    join_pending(&mut ds, &carol);
    let ct = alice.handle.encrypt(&gid, b"after the race").unwrap();
    let (kind, pt) = bob.handle.process(&gid, &ct).unwrap();
    assert_eq!((kind, pt.as_slice()), (1u8, b"after the race".as_slice()));
    let (kind, pt) = carol.handle.process(&gid, &ct).unwrap();
    assert_eq!((kind, pt.as_slice()), (1u8, b"after the race".as_slice()));
}

/// A device that was offline replays missed handshakes in order and catches up.
#[test]
fn offline_device_replays_handshakes() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "phone");
    let bob = Device::new("bob", "desktop");
    let carol = Device::new("carol", "phone");
    let gid = b"chat-3".to_vec();

    for d in [&alice, &bob, &carol] {
        d.publish(&mut ds, 3);
    }
    alice.handle.create_group(&gid).unwrap();
    add_member(&mut ds, &alice, &bob, &gid).unwrap();
    join_pending(&mut ds, &bob);

    let bob_epoch_when_he_went_offline = bob.handle.epoch(&gid).unwrap();

    // Bob is offline while Alice adds Carol and rotates her key.
    add_member(&mut ds, &alice, &carol, &gid).unwrap();
    join_pending(&mut ds, &carol);

    assert!(
        alice.handle.epoch(&gid).unwrap() > bob_epoch_when_he_went_offline,
        "the group moved on without bob"
    );

    // Bob reconnects and replays.
    for hs in ds.handshakes_since(bob_epoch_when_he_went_offline) {
        bob.handle.process(&gid, &hs).unwrap();
    }
    assert_eq!(bob.handle.epoch(&gid).unwrap(), alice.handle.epoch(&gid).unwrap());

    let ct = carol.handle.encrypt(&gid, b"hello from carol").unwrap();
    let (kind, pt) = bob.handle.process(&gid, &ct).unwrap();
    assert_eq!((kind, pt.as_slice()), (1u8, b"hello from carol".as_slice()));
}

/// Application messages delivered out of order must still decrypt. This is why
/// `out_of_order_tolerance` is raised from the OpenMLS default of 5.
#[test]
fn out_of_order_application_messages_decrypt() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "phone");
    let bob = Device::new("bob", "desktop");
    let gid = b"chat-4".to_vec();

    alice.publish(&mut ds, 2);
    bob.publish(&mut ds, 2);
    alice.handle.create_group(&gid).unwrap();
    add_member(&mut ds, &alice, &bob, &gid).unwrap();
    join_pending(&mut ds, &bob);

    let mut cts = Vec::new();
    for i in 0..10 {
        cts.push(alice.handle.encrypt(&gid, format!("m{i}").as_bytes()).unwrap());
    }
    cts.reverse(); // worst case: fully reversed delivery

    let mut seen = 0;
    for ct in &cts {
        if let Ok((1, _)) = bob.handle.process(&gid, ct) {
            seen += 1;
        }
    }
    assert_eq!(seen, 10, "all 10 must decrypt despite reversed delivery");
}

/// Multi-device, one account: the case the user reported broken for a whole
/// session — a message sent from one device must be readable on their other one.
#[test]
fn own_other_device_reads_the_message() {
    let mut ds = FakeDs::new();
    let phone = Device::new("alice", "phone");
    let desktop = Device::new("alice", "desktop");
    let bob = Device::new("bob", "phone");
    let gid = b"chat-5".to_vec();

    for d in [&phone, &desktop, &bob] {
        d.publish(&mut ds, 3);
    }

    phone.handle.create_group(&gid).unwrap();
    add_member(&mut ds, &phone, &desktop, &gid).expect("add own desktop");
    join_pending(&mut ds, &desktop);
    add_member(&mut ds, &phone, &bob, &gid).expect("add bob");
    join_pending(&mut ds, &bob);

    // Every existing member must apply the commit that added bob, or it stays
    // an epoch behind and can decrypt nothing afterwards.
    catch_up(&ds, &desktop, &gid);
    assert_eq!(
        desktop.handle.epoch(&gid).unwrap(),
        phone.handle.epoch(&gid).unwrap(),
        "all devices must converge on one epoch"
    );

    let roster = String::from_utf8(phone.handle.roster(&gid).unwrap()).unwrap();
    for want in ["alice|phone", "alice|desktop", "bob|phone"] {
        assert!(roster.contains(want), "roster missing {want}: {roster}");
    }

    let ct = phone.handle.encrypt(&gid, b"from my phone").unwrap();
    let (kind, pt) = desktop.handle.process(&gid, &ct).unwrap();
    assert_eq!(
        (kind, pt.as_slice()),
        (1u8, b"from my phone".as_slice()),
        "my own other device must read my message"
    );
}


// ------------------------------------------------- invite batch isolation
//
// A live JOIN failed with "invalid key package: A key package extension is not
// supported in the leaf's capabilities" - a legacy mlspp package sitting in
// another device's pool. add_members is all-or-nothing, so that one package
// rejected the whole Add, and every package in the batch had already been
// consumed server-side by then. One stale device kept a healthy device out.
//
// These pin the contract the fix relies on: rejection never touches the group,
// and a caller can find the bad package without staging anything.

/// A real KeyPackage captured from the deployment whose JOIN broke.
///
/// Published by the Windows client's mlspp stack, it is structurally valid TLS -
/// it deserializes cleanly - and is rejected only at the semantic step, with
/// "A key package extension is not supported in the leaf's capabilities". That
/// distinction is the whole point: a fixture that merely fails to PARSE passes
/// these tests while leaving the real failure mode uncovered - exactly the
/// mistake the first version of this file made, where two mutations of the
/// validate path survived.
///
/// Public material only: a published KeyPackage is handed to any claimer on
/// request and carries no private key.
const LEGACY_MLSPP_KEY_PACKAGE: &str = concat!(
    "0001000120f9518a4283b990c1e4727f24cbf600d3758473cb59a5cea8df15b4972221862c20",
    "90d5bc9cc1e5d32f36440e8f18ef7e101dbbce530f6b89f6412c3fa64118ec7720d7afa154f7",
    "046a8287fb74d573ee9feebe36203a0d7f8770e78add5478ad890f0001404961333836343032",
    "302d393064622d346463642d383865382d3430373331636535313432637c6533303265616138",
    "2d373766302d346337632d613661362d31373338323130313437396402000116000100020003",
    "000500071a1a0006000400080009000a04dada1a1a021a1a0a00013a3a0002fe00ff00010000",
    "000000000000ffffffffffffffff13dada108d42351d343729f10d7d70bf3588dd98404002ac",
    "cc3e6775b2f5dfab6218b1b93fd7237324e0ae381c056fd1494e8e8da1ca81c0c4fc4e32e77b",
    "f26817334792316457e6066d29fe6529d7311fd23ec77f0a0e1a1a0b2b21045e08501f7ca4e8",
    "4b4040116d60d595cb9f338087441e2b14131cf6cfe6b1be1bf4830e8d62632c9ffbcfdbf6f4",
    "cc0b3e5c43e6018a2ae27e09081f55b90ac871d937541c433bfa064a0d",
);

fn poison_key_package() -> Vec<u8> {
    (0..LEGACY_MLSPP_KEY_PACKAGE.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&LEGACY_MLSPP_KEY_PACKAGE[i..i + 2], 16).expect("hex"))
        .collect()
}

/// A package the core cannot use, for cases where the reason does not matter.
fn unusable_key_package(good: &[u8]) -> Vec<u8> {
    good[..good.len() / 2].to_vec()
}

#[test]
fn validate_key_package_separates_usable_from_unusable() {
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let good = bob.handle.key_package().expect("kp");

    assert!(
        alice.handle.validate_key_package(&good).is_ok(),
        "a freshly published package must validate"
    );
    assert!(
        alice.handle.validate_key_package(&unusable_key_package(&good)).is_err(),
        "a package the core cannot use must be reported, not accepted"
    );
    assert!(
        alice.handle.validate_key_package(&[]).is_err(),
        "empty input is not a key package"
    );
}

#[test]
fn a_rejected_batch_leaves_the_group_completely_untouched() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let gid = b"g-untouched".to_vec();
    alice.handle.create_group(&gid).expect("create");

    let epoch_before = alice.handle.epoch(&gid).expect("epoch");
    let good = bob.handle.key_package().expect("kp");

    let err = alice
        .handle
        .add_members(&gid, &[good.clone(), unusable_key_package(&good)])
        .expect_err("a batch containing an unusable package must be rejected");
    assert!(format!("{err}").contains("key package"), "unexpected error: {err}");

    assert_eq!(
        epoch_before,
        alice.handle.epoch(&gid).expect("epoch"),
        "a rejected batch must not advance the epoch"
    );
    // Nothing was staged, so the group is immediately usable for a real Add.
    let kp = bob.handle.key_package().expect("kp");
    let (commit, welcome) = alice
        .handle
        .add_members(&gid, &[kp])
        .expect("the group must still be usable after a rejected batch");
    assert!(ds.submit_commit(epoch_before, commit) == SubmitResult::Accepted { new_epoch: 1 });
    alice.handle.merge_pending(&gid).expect("merge");
    ds.queue_welcome(&bob.id, welcome);
    let roster = String::from_utf8(alice.handle.roster(&gid).expect("roster")).unwrap();
    assert!(roster.contains(&bob.id), "bob must be a member: {roster}");
}

#[test]
fn one_unusable_package_must_not_keep_valid_devices_out() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let carol = Device::new("carol", "c1");
    let gid = b"g-isolation".to_vec();
    alice.handle.create_group(&gid).expect("create");

    // The batch a real invite builds: two healthy devices and one stale one.
    let bob_kp = bob.handle.key_package().expect("kp");
    let carol_kp = carol.handle.key_package().expect("kp");
    let stale = unusable_key_package(&bob.handle.key_package().expect("kp"));
    let batch = vec![bob_kp.clone(), stale.clone(), carol_kp.clone()];

    // Old behaviour: the whole invite dies.
    assert!(
        alice.handle.add_members(&gid, &batch).is_err(),
        "batching an unusable package must still fail - that is why filtering is needed"
    );

    // New behaviour: filter first, then one commit for the survivors.
    let usable: Vec<Vec<u8>> = batch
        .iter()
        .filter(|kp| alice.handle.validate_key_package(kp).is_ok())
        .cloned()
        .collect();
    assert_eq!(2, usable.len(), "both healthy devices must survive the filter");

    let expected = alice.handle.epoch(&gid).expect("epoch");
    let (commit, welcome) = alice
        .handle
        .add_members(&gid, &usable)
        .expect("the filtered batch must commit");
    assert_eq!(
        SubmitResult::Accepted { new_epoch: 1 },
        ds.submit_commit(expected, commit),
        "one commit for all survivors, not one per device"
    );
    alice.handle.merge_pending(&gid).expect("merge");
    ds.queue_welcome(&bob.id, welcome.clone());
    ds.queue_welcome(&carol.id, welcome);

    // Both healthy devices actually join and converge on the group.
    for dev in [&bob, &carol] {
        let joined = join_pending(&mut ds, dev);
        assert_eq!(1, joined.len(), "{} must join from the Welcome", dev.id);
        assert_eq!(gid, joined[0], "{} joined the wrong group", dev.id);
    }
    let roster = String::from_utf8(alice.handle.roster(&gid).expect("roster")).unwrap();
    assert_eq!(3, roster.lines().count(), "alice, bob and carol must all be members: {roster}");
    assert!(
        roster.contains(&bob.id) && roster.contains(&carol.id),
        "both healthy devices must be in the roster: {roster}"
    );
}

#[test]
fn a_device_whose_package_was_rejected_can_still_join_later() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let gid = b"g-retry".to_vec();
    alice.handle.create_group(&gid).expect("create");

    // First attempt: bob's published package is unusable, so he is filtered out
    // and the invite adds nobody.
    let bad = unusable_key_package(&bob.handle.key_package().expect("kp"));
    let usable: Vec<Vec<u8>> = [bad]
        .iter()
        .filter(|kp| alice.handle.validate_key_package(kp).is_ok())
        .cloned()
        .collect();
    assert!(usable.is_empty(), "the unusable package must be filtered out");
    assert_eq!(0, alice.handle.epoch(&gid).expect("epoch"), "no commit, no epoch change");

    // Bob republishes something healthy; the next invite pass must add him.
    bob.publish(&mut ds, 1);
    add_member(&mut ds, &alice, &bob, &gid).expect("retry must succeed");
    let joined = join_pending(&mut ds, &bob);
    assert_eq!(vec![gid.clone()], joined, "bob joins on the retry");
    let roster = String::from_utf8(alice.handle.roster(&gid).expect("roster")).unwrap();
    assert!(roster.contains(&bob.id), "bob must be a member after the retry: {roster}");
}

#[test]
fn independent_devices_get_independent_mls_identities() {
    // Two devices of the SAME account must never share a signing identity: that
    // is what made one emulator publish packages carrying another account's
    // credential.
    let phone = Device::new("koueosh", "phone");
    let emulator = Device::new("koueosh", "emulator");

    let a = phone.handle.key_package().expect("kp");
    let b = emulator.handle.key_package().expect("kp");
    assert_ne!(a, b, "two devices must not publish identical key packages");

    // Each validates against the other - they are legitimate, just distinct.
    assert!(phone.handle.validate_key_package(&b).is_ok());
    assert!(emulator.handle.validate_key_package(&a).is_ok());
    assert_ne!(phone.id, emulator.id);
}


#[test]
fn the_real_legacy_package_is_well_formed_but_semantically_rejected() {
    let alice = Device::new("alice", "a1");
    let err = alice
        .handle
        .validate_key_package(&poison_key_package())
        .expect_err("the captured legacy package must be rejected");
    let msg = format!("{err}");
    assert!(
        msg.contains("invalid key package"),
        "expected the semantic validate failure, got: {msg}"
    );
    assert!(
        !msg.contains("bad key package"),
        "this fixture must reach the validate step, not fail at deserialize: {msg}"
    );
}

#[test]
fn a_legacy_package_must_not_poison_a_healthy_join() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let gid = b"g-legacy-poison".to_vec();
    alice.handle.create_group(&gid).expect("create");

    let bob_kp = bob.handle.key_package().expect("kp");
    let batch = vec![poison_key_package(), bob_kp.clone()];

    assert!(
        alice.handle.add_members(&gid, &batch).is_err(),
        "a batch containing the legacy package must be rejected wholesale"
    );
    assert_eq!(0, alice.handle.epoch(&gid).expect("epoch"), "no epoch change");

    let usable: Vec<Vec<u8>> = batch
        .iter()
        .filter(|kp| alice.handle.validate_key_package(kp).is_ok())
        .cloned()
        .collect();
    assert_eq!(1, usable.len(), "only bob's package survives the filter");

    let expected = alice.handle.epoch(&gid).expect("epoch");
    let (commit, welcome) = alice.handle.add_members(&gid, &usable).expect("add");
    assert_eq!(SubmitResult::Accepted { new_epoch: 1 }, ds.submit_commit(expected, commit));
    alice.handle.merge_pending(&gid).expect("merge");
    ds.queue_welcome(&bob.id, welcome);
    assert_eq!(vec![gid.clone()], join_pending(&mut ds, &bob), "bob must join");

    let roster = String::from_utf8(alice.handle.roster(&gid).expect("roster")).unwrap();
    assert!(roster.contains(&bob.id), "bob must be a member: {roster}");
}


// -------------------------------------------- duplicate signature keys
//
// A second live JOIN failed with "Duplicate signature key in proposals and
// group": two orphaned devices, left behind by an account switch that reused the
// previous account MLS store, both presented the same signature key. Each
// package was individually valid, so per-package validation could not see it -
// the constraint exists only relative to the batch and the group.

/// Filters a candidate batch the way inviteMissingDevices must: drop anything
/// the core rejects, then drop anything whose signature key is already in the
/// group or was already accepted earlier in this batch.
fn admissible(adder: &Device, gid: &[u8], candidates: &[Vec<u8>]) -> Vec<Vec<u8>> {
    let mut seen: Vec<Vec<u8>> = adder.handle.group_signature_keys(gid).unwrap_or_default();
    let mut out = Vec::new();
    for kp in candidates {
        let key = match adder.handle.key_package_signature_key(kp) {
            Ok(k) => k,
            Err(_) => continue,
        };
        if seen.contains(&key) {
            continue;
        }
        seen.push(key);
        out.push(kp.clone());
    }
    out
}

#[test]
fn signature_key_is_stable_per_device_and_unique_across_devices() {
    let a = Device::new("u", "d1");
    let b = Device::new("u", "d2");

    let a1 = a.handle.key_package_signature_key(&a.handle.key_package().unwrap()).unwrap();
    let a2 = a.handle.key_package_signature_key(&a.handle.key_package().unwrap()).unwrap();
    let b1 = a.handle.key_package_signature_key(&b.handle.key_package().unwrap()).unwrap();

    assert_eq!(a1, a2, "one device packages must share its signature key");
    assert_ne!(a1, b1, "two devices must not share a signature key");
    assert!(
        a.handle.key_package_signature_key(&poison_key_package()).is_err(),
        "an unusable package must not yield a signature key"
    );
}

#[test]
fn a_candidate_already_in_the_group_is_skipped_not_fatal() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let carol = Device::new("carol", "c1");
    let gid = b"g-dup-member".to_vec();
    alice.handle.create_group(&gid).expect("create");

    bob.publish(&mut ds, 1);
    add_member(&mut ds, &alice, &bob, &gid).expect("bob joins");

    let batch = vec![
        bob.handle.key_package().expect("kp"),
        carol.handle.key_package().expect("kp"),
    ];
    assert!(
        alice.handle.add_members(&gid, &batch).is_err(),
        "re-proposing an existing member must reject the whole batch"
    );

    let usable = admissible(&alice, &gid, &batch);
    assert_eq!(1, usable.len(), "bob is filtered out, carol survives");

    let expected = alice.handle.epoch(&gid).expect("epoch");
    let (commit, welcome) = alice.handle.add_members(&gid, &usable).expect("add");
    assert_eq!(
        SubmitResult::Accepted { new_epoch: expected + 1 },
        ds.submit_commit(expected, commit),
        "exactly one commit, one epoch"
    );
    alice.handle.merge_pending(&gid).expect("merge");
    ds.queue_welcome(&carol.id, welcome);
    assert_eq!(vec![gid.clone()], join_pending(&mut ds, &carol), "carol joins");

    let roster = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert_eq!(3, roster.lines().count(), "bob must appear once: {roster}");
}

#[test]
fn two_candidates_sharing_a_signature_key_do_not_block_the_others() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let orphan = Device::new("orphan", "o1");
    let carol = Device::new("carol", "c1");
    let gid = b"g-dup-batch".to_vec();
    alice.handle.create_group(&gid).expect("create");

    let twin_a = orphan.handle.key_package().expect("kp");
    let twin_b = orphan.handle.key_package().expect("kp");
    let carol_kp = carol.handle.key_package().expect("kp");
    assert_eq!(
        alice.handle.key_package_signature_key(&twin_a).unwrap(),
        alice.handle.key_package_signature_key(&twin_b).unwrap(),
        "the twins must genuinely share a signature key"
    );

    let batch = vec![twin_a, twin_b, carol_kp];
    assert!(
        alice.handle.add_members(&gid, &batch).is_err(),
        "duplicate signature keys in one batch must be fatal unfiltered"
    );

    let usable = admissible(&alice, &gid, &batch);
    assert_eq!(2, usable.len(), "one twin plus carol");

    let expected = alice.handle.epoch(&gid).expect("epoch");
    let (commit, welcome) = alice.handle.add_members(&gid, &usable).expect("add");
    assert_eq!(SubmitResult::Accepted { new_epoch: 1 }, ds.submit_commit(expected, commit));
    alice.handle.merge_pending(&gid).expect("merge");
    ds.queue_welcome(&carol.id, welcome);
    assert_eq!(vec![gid.clone()], join_pending(&mut ds, &carol), "carol must still join");
}

#[test]
fn the_full_production_batch_shape_admits_the_healthy_device() {
    // The batch that failed in production: a legacy mlspp package, two orphans
    // sharing one MLS identity, and the one clean device.
    let mut ds = FakeDs::new();
    let mehdi = Device::new("mehdi", "bd63c60a");
    let orphan = Device::new("orphan", "shared");
    let koueosh = Device::new("koueosh", "d22af910");
    let gid = b"g-production".to_vec();
    mehdi.handle.create_group(&gid).expect("create");

    let batch = vec![
        poison_key_package(),
        orphan.handle.key_package().unwrap(),
        koueosh.handle.key_package().unwrap(),
        orphan.handle.key_package().unwrap(),
    ];
    assert!(mehdi.handle.add_members(&gid, &batch).is_err(), "unfiltered this is fatal");

    let usable = admissible(&mehdi, &gid, &batch);
    assert_eq!(2, usable.len(), "one orphan and koueosh survive; the mlspp package does not");

    let expected = mehdi.handle.epoch(&gid).unwrap();
    let (commit, welcome) = mehdi.handle.add_members(&gid, &usable).expect("add");
    assert_eq!(SubmitResult::Accepted { new_epoch: 1 }, ds.submit_commit(expected, commit));
    mehdi.handle.merge_pending(&gid).expect("merge");
    ds.queue_welcome(&koueosh.id, welcome);
    assert_eq!(vec![gid.clone()], join_pending(&mut ds, &koueosh), "koueosh must join");
    let roster = String::from_utf8(mehdi.handle.roster(&gid).unwrap()).unwrap();
    assert!(roster.contains(&koueosh.id), "koueosh must be a member: {roster}");
}

#[test]
fn retrying_an_invite_adds_nobody_twice() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let gid = b"g-idempotent".to_vec();
    alice.handle.create_group(&gid).expect("create");

    bob.publish(&mut ds, 2);
    add_member(&mut ds, &alice, &bob, &gid).expect("bob joins");
    let before = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();

    let again = vec![ds.claim_key_package(&bob.id).expect("kp")];
    assert!(admissible(&alice, &gid, &again).is_empty(), "bob must not be re-added");

    let after = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert_eq!(before, after, "retry must not change the roster");
    assert_eq!(1, alice.handle.epoch(&gid).unwrap(), "retry must not advance the epoch");
}

#[test]
fn a_windows_v6_identity_coexists_with_android_devices() {
    // Platform is irrelevant to MLS: what matters is that each device has its
    // own identity. A clean Windows v6 client is just another device.
    let mut ds = FakeDs::new();
    let android_a = Device::new("mehdi", "android-phone");
    let android_b = Device::new("koueosh", "android-emulator");
    let windows = Device::new("koueosh", "windows-desktop");
    let gid = b"g-mixed".to_vec();
    android_a.handle.create_group(&gid).expect("create");

    let batch = vec![
        android_b.handle.key_package().unwrap(),
        windows.handle.key_package().unwrap(),
    ];
    let usable = admissible(&android_a, &gid, &batch);
    assert_eq!(2, usable.len(), "distinct identities must all be admissible");

    let expected = android_a.handle.epoch(&gid).unwrap();
    let (commit, welcome) = android_a.handle.add_members(&gid, &usable).expect("add");
    assert_eq!(SubmitResult::Accepted { new_epoch: 1 }, ds.submit_commit(expected, commit));
    android_a.handle.merge_pending(&gid).expect("merge");
    for d in [&android_b, &windows] {
        ds.queue_welcome(&d.id, welcome.clone());
        assert_eq!(vec![gid.clone()], join_pending(&mut ds, d), "{} must join", d.id);
    }
    let roster = String::from_utf8(android_a.handle.roster(&gid).unwrap()).unwrap();
    assert_eq!(3, roster.lines().count(), "three devices in one group: {roster}");
}


// ------------------------------------------------- phantom members
//
// A device that is added to the tree but never consumes its Welcome looks like
// a member to everyone else and is therefore never re-invited, while being
// unable to read anything. It is stranded, and only the DS can tell: it holds
// the Welcome with consumed_at still NULL.

#[test]
fn a_phantom_leaf_can_be_removed_and_the_device_re_invited() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let ghost = Device::new("ghost", "g1");
    let gid = b"g-phantom".to_vec();
    alice.handle.create_group(&gid).expect("create");

    // Ghost is added but never joins: the Welcome is queued and left unacked,
    // exactly the state the DS reports as pending.
    ghost.publish(&mut ds, 2);
    add_member(&mut ds, &alice, &ghost, &gid).expect("ghost is added to the tree");
    let roster = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert!(roster.contains(&ghost.id), "ghost must be in the tree: {roster}");
    assert!(
        ds.welcomes.iter().any(|w| w.device == ghost.id && !w.consumed),
        "the DS must still show the Welcome as pending"
    );

    // Evicting the dead leaf is what unblocks recovery.
    let expected = alice.handle.epoch(&gid).unwrap();
    let commit = alice
        .handle
        .remove_members(&gid, &[ghost.id.clone()])
        .expect("the phantom leaf must be removable");
    assert_eq!(
        SubmitResult::Accepted { new_epoch: expected + 1 },
        ds.submit_commit(expected, commit)
    );
    alice.handle.merge_pending(&gid).expect("merge");
    let roster = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert!(!roster.contains(&ghost.id), "ghost must be gone: {roster}");

    // Now the ordinary invite path can add it back for real.
    add_member(&mut ds, &alice, &ghost, &gid).expect("re-invite");
    assert_eq!(vec![gid.clone()], join_pending(&mut ds, &ghost), "ghost joins this time");
    let roster = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert!(roster.contains(&ghost.id), "ghost is a real member now: {roster}");
}

#[test]
fn removing_nobody_is_an_error_not_an_empty_commit() {
    let alice = Device::new("alice", "a1");
    let gid = b"g-noremove".to_vec();
    alice.handle.create_group(&gid).expect("create");
    let before = alice.handle.epoch(&gid).unwrap();

    assert!(
        alice.handle.remove_members(&gid, &["nobody|nowhere".to_string()]).is_err(),
        "a Remove that matches no member must fail rather than burn an epoch"
    );
    assert_eq!(before, alice.handle.epoch(&gid).unwrap(), "epoch must not move");
}

#[test]
fn a_genuinely_joined_member_survives_removal_of_a_phantom() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let real = Device::new("real", "r1");
    let ghost = Device::new("ghost", "g1");
    let gid = b"g-mixed-remove".to_vec();
    alice.handle.create_group(&gid).expect("create");

    real.publish(&mut ds, 1);
    add_member(&mut ds, &alice, &real, &gid).expect("real added");
    join_pending(&mut ds, &real);
    ghost.publish(&mut ds, 1);
    add_member(&mut ds, &alice, &ghost, &gid).expect("ghost added");

    let expected = alice.handle.epoch(&gid).unwrap();
    let commit = alice.handle.remove_members(&gid, &[ghost.id.clone()]).expect("remove");
    assert_eq!(
        SubmitResult::Accepted { new_epoch: expected + 1 },
        ds.submit_commit(expected, commit)
    );
    alice.handle.merge_pending(&gid).expect("merge");
    catch_up(&ds, &real, &gid);

    let roster = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert!(roster.contains(&real.id), "the joined member must survive: {roster}");
    assert!(!roster.contains(&ghost.id), "only the phantom goes: {roster}");
    assert_eq!(
        alice.handle.epoch(&gid).unwrap(),
        real.handle.epoch(&gid).unwrap(),
        "the surviving member must stay in epoch step"
    );
}

// ------------------------------------- phantom detection must not misfire
//
// Being listed as pending is not proof of anything on its own. A device's
// Welcomes are a ledger: an ancient row that can never be opened says nothing
// about a device that has since joined through a newer one, and a row created
// by the commit that just landed says nothing about a device that has not had
// a chance to poll. Reading either as proof evicts genuine members, and since
// the eviction is followed by a re-invite, it does so in a loop.

#[test]
fn a_device_that_consumed_a_newer_welcome_is_not_evicted() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let carol = Device::new("carol", "c1");
    let gid = b"g-superseded".to_vec();
    alice.handle.create_group(&gid).expect("create");

    // W1: an invitation from a dead store incarnation. The matching KeyPackage
    // private key is long gone, so this row can never be consumed — it sits
    // unconsumed for the life of the chat. created_at 0 puts it first.
    ds.queue_welcome_at(&bob.id, vec![0xde, 0xad, 0xbe, 0xef], 0, Some(0));

    // W2: the invitation that actually worked.
    bob.publish(&mut ds, 2);
    add_member(&mut ds, &alice, &bob, &gid).expect("bob added");
    assert_eq!(vec![gid.clone()], join_pending(&mut ds, &bob), "bob joins through W2");
    assert_eq!(
        1,
        ds.get_welcomes(&bob.id).len(),
        "W1 must stay pending: a Welcome that cannot be opened is never acked"
    );

    // Move the group past W2, so the staleness gate is NOT what saves bob here
    // and supersession has to carry the test on its own.
    carol.publish(&mut ds, 1);
    add_member(&mut ds, &alice, &carol, &gid).expect("carol added");
    join_pending(&mut ds, &carol);

    let cov = ds.coverage();
    assert!(cov.acked.contains(&bob.id), "consuming W2 is the join proof: {:?}", cov.acked);
    assert!(
        !cov.pending.contains(&bob.id),
        "the dead W1 must not outrank the consumed W2: {:?}",
        cov.pending
    );
    assert!(phantoms(&ds, &alice, &gid).is_empty(), "a joined member is not a phantom");

    let before = (ds.epoch, ds.handshakes.len(), ds.welcomes.len());
    invite_pass(&mut ds, &alice, &gid, &[&bob, &carol]);
    assert_eq!(
        before,
        (ds.epoch, ds.handshakes.len(), ds.welcomes.len()),
        "no eviction, no epoch advance, no replacement Welcome"
    );

    let roster = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert!(roster.contains(&bob.id), "bob must still be a member: {roster}");

    // The property every epoch assertion above is a proxy for.
    catch_up(&ds, &bob, &gid);
    let ct = alice.handle.encrypt(&gid, b"still here").expect("encrypt");
    let (kind, pt) = bob.handle.process(&gid, &ct).expect("bob decrypts");
    assert_eq!((kind, pt.as_slice()), (1u8, b"still here".as_slice()));
}

#[test]
fn a_device_with_only_a_pending_welcome_stays_evictable() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let ghost = Device::new("ghost", "g1");
    let carol = Device::new("carol", "c1");
    let gid = b"g-still-evictable".to_vec();
    alice.handle.create_group(&gid).expect("create");

    // Ghost gets a leaf and never joins: one unconsumed Welcome, and nothing
    // consumed anywhere in its ledger.
    ghost.publish(&mut ds, 2);
    add_member(&mut ds, &alice, &ghost, &gid).expect("ghost added");

    // The group moves past the invitation, which is what makes it outstanding
    // rather than in flight.
    carol.publish(&mut ds, 1);
    add_member(&mut ds, &alice, &carol, &gid).expect("carol added");
    join_pending(&mut ds, &carol);

    assert!(
        ds.coverage().pending.contains(&ghost.id),
        "a stale unconsumed invitation is exactly the evidence recovery needs"
    );
    assert_eq!(vec![ghost.id.clone()], phantoms(&ds, &alice, &gid));

    invite_pass(&mut ds, &alice, &gid, &[&ghost, &carol]);

    assert_eq!(vec![gid.clone()], join_pending(&mut ds, &ghost), "ghost joins for real");
    catch_up(&ds, &carol, &gid);
    let roster = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert!(roster.contains(&ghost.id), "ghost is a real member now: {roster}");
    assert!(roster.contains(&carol.id), "carol must survive the eviction: {roster}");
}

#[test]
fn a_just_invited_device_is_not_evicted_before_it_can_poll() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let gid = b"g-in-flight".to_vec();
    alice.handle.create_group(&gid).expect("create");

    bob.publish(&mut ds, 2);
    add_member(&mut ds, &alice, &bob, &gid).expect("bob added");
    assert!(!ds.get_welcomes(&bob.id).is_empty(), "the invitation is outstanding");

    assert!(
        ds.coverage().pending.is_empty(),
        "an invitation at the current epoch is in flight, not abandoned"
    );
    assert!(phantoms(&ds, &alice, &gid).is_empty());

    let before = (ds.epoch, ds.handshakes.len(), ds.welcomes.len());
    invite_pass(&mut ds, &alice, &gid, &[&bob]);
    assert_eq!(
        before,
        (ds.epoch, ds.handshakes.len(), ds.welcomes.len()),
        "evicting a device milliseconds after inviting it is how the loop closed"
    );

    assert_eq!(vec![gid.clone()], join_pending(&mut ds, &bob), "bob joins when it polls");
}

/// The regression that matters most: the invite path must reach a fixed point.
///
/// Every other test here asserts a safety property — nobody wrong is removed,
/// nobody right is stranded. None of them asserts liveness, and that is exactly
/// the gap the production defect fell through: each individual eviction looked
/// defensible, and only the fact that they never stopped was wrong.
#[test]
fn the_evict_reinvite_cycle_terminates() {
    let mut ds = FakeDs::new();
    let alice = Device::new("alice", "a1");
    let bob = Device::new("bob", "b1");
    let carol = Device::new("carol", "c1");
    let gid = b"g-terminates".to_vec();
    alice.handle.create_group(&gid).expect("create");

    // The row that can never be consumed. Under "any unconsumed Welcome means
    // pending" this alone makes bob a phantom for the life of the chat.
    ds.queue_welcome_at(&bob.id, vec![0xde, 0xad, 0xbe, 0xef], 0, Some(0));

    bob.publish(&mut ds, 4);
    add_member(&mut ds, &alice, &bob, &gid).expect("bob added");
    join_pending(&mut ds, &bob);

    // A third device moves the group past bob's invitation, so the staleness
    // gate cannot be what holds the loop shut.
    carol.publish(&mut ds, 1);
    add_member(&mut ds, &alice, &carol, &gid).expect("carol added");
    join_pending(&mut ds, &carol);

    let fixed = (ds.epoch, ds.handshakes.len(), ds.welcomes.len());
    for pass in 1..=6 {
        // A real client keeps its published stock topped up, so an eviction
        // loop never runs out of fuel and never stops on its own.
        bob.publish(&mut ds, 1);
        invite_pass(&mut ds, &alice, &gid, &[&bob, &carol]);
        join_pending(&mut ds, &bob);
        catch_up(&ds, &bob, &gid);
        catch_up(&ds, &carol, &gid);

        assert_eq!(
            fixed,
            (ds.epoch, ds.handshakes.len(), ds.welcomes.len()),
            "pass {pass} moved the group: a joined device was evicted and re-invited. \
             That is the loop that walked a live group from epoch 4 to epoch 8."
        );
    }

    let roster = String::from_utf8(alice.handle.roster(&gid).unwrap()).unwrap();
    assert!(roster.contains(&bob.id), "bob must still be a member: {roster}");
    assert_eq!(
        1,
        ds.get_welcomes(&bob.id).len(),
        "the dead Welcome stays pending forever and must simply stop mattering"
    );

    let ct = alice.handle.encrypt(&gid, b"converged").expect("encrypt");
    let (kind, pt) = bob.handle.process(&gid, &ct).expect("bob decrypts");
    assert_eq!((kind, pt.as_slice()), (1u8, b"converged".as_slice()));
}

// ---------------------------------------------------------------- external commit
//
// A device that authored a commit the DS accepted, but lost the merge before it
// reached storage, is stranded: it cannot replay its own handshake (the sender
// discards its copy for forward secrecy) and it cannot skip ahead. A Welcome
// cannot rescue it either, because MLS refuses to add an existing member.
//
// External commit is the escape, and the property that matters is that it costs
// the group an epoch WITHOUT costing it a member.

/// Reproduces the stranded-author state and returns
/// `(ds, alice, bob, gid, bob_pre_commit_snapshot, bobs_own_commit)`.
fn strand_the_author() -> (FakeDs, Device, Device, Vec<u8>, Vec<u8>, Vec<u8>) {
    let mut ds = FakeDs::new();
    let alice = Device::new("u1", "alice");
    let bob = Device::new("u1", "bob");
    let carol = Device::new("u2", "carol");
    let gid = b"chat-ext".to_vec();

    alice.handle.create_group(&gid).expect("create");
    bob.publish(&mut ds, 4);
    carol.publish(&mut ds, 4);
    add_member(&mut ds, &alice, &bob, &gid).expect("add bob");
    join_pending(&mut ds, &bob);
    add_member(&mut ds, &alice, &carol, &gid).expect("add carol");
    join_pending(&mut ds, &carol);
    catch_up(&ds, &bob, &gid);

    // Bob authors a commit, the DS accepts it, but his merge is never persisted.
    let pre = bob.handle.snapshot().expect("snapshot");
    let commit = bob
        .handle
        .remove_members(&gid, &[carol.id.clone()])
        .expect("stage remove");
    assert!(matches!(
        ds.submit_commit(bob.handle.epoch(&gid).unwrap(), commit.clone()),
        SubmitResult::Accepted { .. }
    ));
    bob.handle.merge_pending(&gid).expect("merge in memory only");
    catch_up(&ds, &alice, &gid);

    (ds, alice, bob, gid, pre, commit)
}

#[test]
fn an_author_cannot_replay_its_own_commit() {
    let (_ds, _alice, _bob, gid, pre, own_commit) = strand_the_author();
    let stranded = ClientHandle::restore(&pre, "u1", "bob").expect("restore");
    stranded.load_group(&gid).expect("stale group loads");

    let err = stranded
        .process(&gid, &own_commit)
        .expect_err("replaying our own commit must fail");
    assert!(
        err.to_string().contains("Cannot decrypt own messages"),
        "unexpected error: {err}"
    );
}

#[test]
fn external_commit_recovers_the_author_without_changing_membership() {
    let (mut ds, alice, _bob, gid, pre, _own) = strand_the_author();

    let roster_before = sorted_roster(&alice.handle, &gid);
    let keys_before = sorted_keys(&alice.handle, &gid);
    let epoch_before = alice.handle.epoch(&gid).unwrap();

    let info = alice.handle.export_group_info(&gid).expect("group info");
    assert!(!info.is_empty(), "GroupInfo must carry the ratchet tree");

    let stranded = ClientHandle::restore(&pre, "u1", "bob").expect("restore");
    stranded.load_group(&gid).ok();
    stranded.drop_group(&gid).expect("drop the stale group");
    let (rgid, commit) = stranded.external_join(&info).expect("external join");
    assert_eq!(rgid, gid, "external commit must target the same group");

    assert!(matches!(
        ds.submit_commit(epoch_before, commit.clone()),
        SubmitResult::Accepted { .. }
    ), "ds must accept the external commit");
    let bob_epoch = stranded.merge_pending(&gid).expect("merge");
    alice.handle.process(&gid, &commit).expect("alice applies it");

    assert_eq!(
        alice.handle.epoch(&gid).unwrap(),
        epoch_before + 1,
        "an external commit costs exactly one epoch"
    );
    assert_eq!(bob_epoch, alice.handle.epoch(&gid).unwrap());
    assert_eq!(
        sorted_roster(&alice.handle, &gid),
        roster_before,
        "membership set must be unchanged: the stale leaf is replaced, not added to"
    );
    assert_eq!(
        sorted_keys(&alice.handle, &gid),
        keys_before,
        "the recovered device reuses its signature key, so even the key set is identical"
    );

    // and it works: the recovered device can read and write again.
    let ct = alice.handle.encrypt(&gid, b"welcome back").expect("encrypt");
    let (kind, pt) = stranded.process(&gid, &ct).expect("recovered device decrypts");
    assert_eq!((kind, pt.as_slice()), (1u8, b"welcome back".as_slice()));
    let ct2 = stranded.encrypt(&gid, b"thanks").expect("encrypt");
    let (_, pt2) = alice.handle.process(&gid, &ct2).expect("alice decrypts");
    assert_eq!(pt2.as_slice(), b"thanks".as_slice());
}

#[test]
fn a_rejected_external_commit_leaves_the_group_untouched() {
    let (mut ds, alice, _bob, gid, pre, _own) = strand_the_author();
    let info = alice.handle.export_group_info(&gid).expect("group info");
    let epoch_before = alice.handle.epoch(&gid).unwrap();
    let roster_before = sorted_roster(&alice.handle, &gid);

    let stranded = ClientHandle::restore(&pre, "u1", "bob").expect("restore");
    stranded.load_group(&gid).ok();
    stranded.drop_group(&gid).expect("drop");
    let (_, commit) = stranded.external_join(&info).expect("external join");

    // Someone else lands a commit first, so ours is stale.
    assert!(
        matches!(
            ds.submit_commit(epoch_before - 1, commit),
            SubmitResult::Conflict { .. }
        ),
        "the DS must fence a stale external commit like any other"
    );
    assert_eq!(alice.handle.epoch(&gid).unwrap(), epoch_before);
    assert_eq!(sorted_roster(&alice.handle, &gid), roster_before);
}

#[test]
fn external_join_rejects_input_that_is_not_group_info() {
    let (_ds, alice, _bob, gid, pre, own_commit) = strand_the_author();
    let stranded = ClientHandle::restore(&pre, "u1", "bob").expect("restore");
    stranded.drop_group(&gid).ok();

    assert!(
        stranded.external_join(&[]).is_err(),
        "empty input must not be treated as GroupInfo"
    );
    assert!(
        stranded.external_join(b"not an mls message").is_err(),
        "garbage must not be treated as GroupInfo"
    );
    // A well-formed MLS message of the WRONG kind must also be refused.
    let err = stranded
        .external_join(&own_commit)
        .expect_err("a commit is not GroupInfo");
    assert!(
        err.to_string().contains("not a GroupInfo message"),
        "unexpected error: {err}"
    );
    let _ = alice;
}

#[test]
fn export_group_info_requires_a_loaded_group() {
    let alice = Device::new("u1", "alice");
    assert!(
        alice.handle.export_group_info(b"no-such-group").is_err(),
        "exporting from a group we do not hold must fail, not panic"
    );
}

fn sorted_roster(c: &ClientHandle, gid: &[u8]) -> Vec<String> {
    let mut v: Vec<String> = String::from_utf8(c.roster(gid).unwrap())
        .unwrap()
        .split('\n')
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .collect();
    v.sort();
    v
}

fn sorted_keys(c: &ClientHandle, gid: &[u8]) -> Vec<Vec<u8>> {
    let mut v = c.group_signature_keys(gid).unwrap();
    v.sort();
    v
}
