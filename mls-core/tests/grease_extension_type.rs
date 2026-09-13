//! Regression tests for GREASE handling in MLS capability validation.
//!
//! Background. RFC 9420 §7.2 requires that "the types of any non-default
//! extensions that appear in the extensions field of a LeafNode MUST be
//! included in the extensions field of the capabilities field", and §7.3
//! validates that by "checking that the ID for each extension in the extensions
//! field is listed in the capabilities.extensions field". The check is by ID,
//! and the RFC states no GREASE exemption.
//!
//! OpenMLS 0.8.1 (and 0.9.0, and main) got that comparison wrong for GREASE
//! values only. `capabilities.extensions` is a `Vec<ExtensionType>`, so a
//! GREASE number in it deserializes to `ExtensionType::Grease(v)`. The same
//! number used as an actual extension deserializes to `Extension::Unknown(v, _)`
//! - deliberately, pinned by upstream's own `codec.rs` round-trip test - and so
//! reports `ExtensionType::Unknown(v)`. Those are distinct enum variants, so a
//! peer that declares a GREASE extension and then uses it was rejected with
//! `LeafNodeValidationError::UnsupportedExtensions` /
//! `ExtensionsNotInCapabilities`. That is the Windows(mlspp) -> Android Welcome
//! failure.
//!
//! The fix normalises the used extension's type through the crate's own
//! canonical `u16` conversion before the lookup, which is the identity on every
//! variant except `Unknown(g)` for GREASE `g`. See
//! `third_party/openmls-0.8.1/PROVENANCE.md`.
//!
//! These tests drive the real `Capabilities::contains_extensions()`. That
//! function is `pub(crate)`, so they reach it through the only public entry
//! point that does not require building a group:
//! `MlsGroupCreateConfigBuilder::with_leaf_node_extensions()`, which calls it
//! directly and returns `LeafNodeValidationError::ExtensionsNotInCapabilities`
//! when it fails. Nothing here reimplements the comparison.

use openmls::prelude::{
    ApplicationIdExtension, Capabilities, Extension, ExtensionType, Extensions, LeafNode,
    MlsGroupCreateConfig, UnknownExtension,
};
use openmls_traits::grease::{is_grease_value, GREASE_VALUES};

/// A non-GREASE value used for the "ordinary unknown extension" cases.
const PLAIN_UNKNOWN: u16 = 0xF042;

/// Ask the real capability check whether `declared` covers `used`.
///
/// `true`  => accepted (`contains_extensions` returned true)
/// `false` => rejected (`LeafNodeValidationError::ExtensionsNotInCapabilities`)
fn capabilities_cover(declared: Vec<ExtensionType>, used: Vec<Extension>) -> bool {
    let capabilities = Capabilities::builder().extensions(declared).build();
    let used = Extensions::<LeafNode>::from_vec(used).expect("valid leaf-node extension list");

    MlsGroupCreateConfig::builder()
        .capabilities(capabilities)
        .with_leaf_node_extensions(used)
        .is_ok()
}

fn grease_extension(value: u16) -> Extension {
    Extension::Unknown(value, UnknownExtension(vec![0x2b, 0x21, 0x04]))
}

// ---------------------------------------------------------------------------
// 1. GREASE used + declared -> ACCEPT
// ---------------------------------------------------------------------------

/// The decisive case. RFC 9420 §7.3: the ID is listed in
/// `capabilities.extensions`, so the leaf supports what it uses.
///
/// This is the assertion that fails against pristine OpenMLS 0.8.1, for every
/// one of the 15 GREASE values.
#[test]
fn grease_used_and_declared_is_accepted() {
    for &value in &GREASE_VALUES {
        assert!(
            capabilities_cover(vec![ExtensionType::Grease(value)], vec![grease_extension(value)]),
            "GREASE 0x{value:04x} is declared in capabilities and used; RFC 9420 §7.3 \
             requires it to be accepted"
        );
    }
}

/// The same, with the GREASE entry sitting among other declared extensions -
/// the shape a real peer sends.
#[test]
fn grease_used_and_declared_alongside_others_is_accepted() {
    for &value in &GREASE_VALUES {
        assert!(
            capabilities_cover(
                vec![
                    ExtensionType::Unknown(PLAIN_UNKNOWN),
                    ExtensionType::Grease(value),
                    ExtensionType::ApplicationId,
                ],
                vec![grease_extension(value)],
            ),
            "GREASE 0x{value:04x} declared among other capabilities must still be found"
        );
    }
}

// ---------------------------------------------------------------------------
// 2. GREASE used + undeclared -> REJECT
// ---------------------------------------------------------------------------

/// The anti-regression half. RFC 9420 §7.2's MUST has no GREASE exemption, so
/// an undeclared GREASE extension stays a violation. A fix that filtered GREASE
/// out of the check instead of normalising it would wrongly accept these.
#[test]
fn grease_used_but_undeclared_is_rejected() {
    for &value in &GREASE_VALUES {
        assert!(
            !capabilities_cover(vec![], vec![grease_extension(value)]),
            "GREASE 0x{value:04x} is used but never declared; §7.2 requires rejection"
        );
    }
}

/// Declaring *a* GREASE value must not vouch for a *different* one: the match
/// is per-ID, not per-variant.
#[test]
fn declaring_one_grease_value_does_not_cover_another() {
    for (i, &value) in GREASE_VALUES.iter().enumerate() {
        let other = GREASE_VALUES[(i + 1) % GREASE_VALUES.len()];
        assert_ne!(value, other);
        assert!(
            !capabilities_cover(vec![ExtensionType::Grease(other)], vec![grease_extension(value)]),
            "declaring GREASE 0x{other:04x} must not cover used GREASE 0x{value:04x}"
        );
    }
}

// ---------------------------------------------------------------------------
// 3. non-GREASE unknown used + declared -> ACCEPT
// ---------------------------------------------------------------------------

#[test]
fn plain_unknown_used_and_declared_is_accepted() {
    assert!(!is_grease_value(PLAIN_UNKNOWN));
    assert!(
        capabilities_cover(
            vec![ExtensionType::Unknown(PLAIN_UNKNOWN)],
            vec![Extension::Unknown(PLAIN_UNKNOWN, UnknownExtension(vec![9]))],
        ),
        "an ordinary unknown extension that is declared must be accepted"
    );
}

// ---------------------------------------------------------------------------
// 4. non-GREASE unknown used + undeclared -> REJECT
// ---------------------------------------------------------------------------

/// The security-critical row: the fix must not turn unknown extensions into a
/// free pass through capability validation.
#[test]
fn plain_unknown_used_but_undeclared_is_rejected() {
    assert!(!is_grease_value(PLAIN_UNKNOWN));
    assert!(
        !capabilities_cover(
            vec![],
            vec![Extension::Unknown(PLAIN_UNKNOWN, UnknownExtension(vec![9]))],
        ),
        "an undeclared unknown extension must still be rejected"
    );

    // ...and declaring some other unknown value must not help either.
    assert!(
        !capabilities_cover(
            vec![ExtensionType::Unknown(0x1234)],
            vec![Extension::Unknown(PLAIN_UNKNOWN, UnknownExtension(vec![9]))],
        ),
        "declaring a different unknown extension must not cover this one"
    );
}

// ---------------------------------------------------------------------------
// 5 & 6. standard/default extension, declared and undeclared -> ACCEPT
// ---------------------------------------------------------------------------

/// `application_id` is a default extension, so RFC 9420 §7.2's requirement
/// ("non-default extensions") does not apply to it. It is accepted either way;
/// `ExtensionType::is_default()` short-circuits the lookup.
#[test]
fn default_extension_is_accepted_whether_declared_or_not() {
    let app_id = || Extension::ApplicationId(ApplicationIdExtension::new(b"regression"));

    assert!(
        capabilities_cover(vec![ExtensionType::ApplicationId], vec![app_id()]),
        "a declared default extension must be accepted"
    );
    assert!(
        capabilities_cover(vec![], vec![app_id()]),
        "a default extension needs no declaration: §7.2 constrains non-default extensions only"
    );
}

// ---------------------------------------------------------------------------
// Supporting invariants
// ---------------------------------------------------------------------------

/// The representation split that caused the defect is deliberate upstream and
/// must stay untouched: the fix belongs in the comparison, not here. Pinned so
/// nobody "fixes" `extension_type()` again - doing so makes GREASE-bearing
/// packages fail to deserialize instead.
#[test]
fn a_used_grease_extension_still_reports_unknown() {
    for &value in &GREASE_VALUES {
        assert_eq!(
            grease_extension(value).extension_type(),
            ExtensionType::Unknown(value),
            "0x{value:04x}: extension_type() must keep reporting Unknown"
        );
        assert_eq!(ExtensionType::from(value), ExtensionType::Grease(value));
    }
}

/// The normalisation the fix relies on is the identity everywhere except
/// GREASE, so no other extension type changes behaviour.
#[test]
fn canonical_normalisation_is_identity_except_for_grease() {
    let unchanged = [
        ExtensionType::ApplicationId,
        ExtensionType::RatchetTree,
        ExtensionType::RequiredCapabilities,
        ExtensionType::ExternalPub,
        ExtensionType::ExternalSenders,
        ExtensionType::LastResort,
        ExtensionType::Unknown(PLAIN_UNKNOWN),
        ExtensionType::Unknown(0x1234),
    ];
    for e in unchanged {
        assert_eq!(ExtensionType::from(u16::from(e)), e, "{e:?} must be unchanged");
    }
    for &value in &GREASE_VALUES {
        assert_eq!(
            ExtensionType::from(u16::from(ExtensionType::Unknown(value))),
            ExtensionType::Grease(value),
            "0x{value:04x}: the only value the normalisation moves"
        );
    }
}

/// The GREASE set is RFC 9420 §13.5's, so the predicate cannot silently widen.
#[test]
fn grease_value_set_is_the_expected_fifteen() {
    assert_eq!(GREASE_VALUES.len(), 15);
    for &v in &GREASE_VALUES {
        assert!(is_grease_value(v));
        assert_eq!(v & 0x0F0F, 0x0A0A, "0x{v:04x} should match the 0x?A?A pattern");
    }
    assert!(GREASE_VALUES.contains(&39578)); // 0x9A9A - DIAG1
    assert!(GREASE_VALUES.contains(&2570)); //  0x0A0A - DIAG1
    assert!(GREASE_VALUES.contains(&56026)); // 0xDADA - legacy mlspp fixture
    assert!(!is_grease_value(PLAIN_UNKNOWN));
}

// ===========================================================================
// SECOND SITE: LeafNode::supports_extension()
// ===========================================================================
//
// `Capabilities::contains_extensions()` above guards a LeafNode's own
// extensions. A second, separate comparison guards a KeyPackage's
// `extensions` field against that KeyPackage's leaf capabilities:
// `LeafNode::supports_extension()` (leaf_node.rs), reached from
// `KeyPackageIn::validate()` (key_packages/key_package_in.rs). It carries the
// identical variant-vs-ID asymmetry, and it is what rejects the captured
// mlspp KeyPackage.
//
// A note on justification, kept deliberately narrow. RFC 9420 7.2/7.3 govern
// the FIRST site and require comparison by extension ID. For the
// KeyPackage-extension path the RFC imposes no equivalent declaration
// requirement (10.1 lists no such check, and 13.4 says an implementation that
// does not understand an extension type MUST ignore it) - OpenMLS is stricter
// than the RFC here. So the justification for normalising this site is NOT
// "the RFC mandates this check", it is:
//
//   * comparing by numeric ID is internally consistent with how OpenMLS
//     represents GREASE (`Grease(v)` when read as a type, `Unknown(v)` when
//     read as a concrete extension - the same wire ID either way), and
//   * it preserves every non-GREASE outcome unchanged.
//
// These tests drive the real path: a KeyPackage is built with the public
// builder, serialized to bytes, and validated through `KeyPackageIn`, which is
// exactly what `mls_core::Client::validate_key_package` does.

use openmls::prelude::tls_codec::{Deserialize as TlsDeserialize, Serialize as TlsSerialize};
use openmls::prelude::{
    BasicCredential, Ciphersuite, CredentialWithKey, KeyPackage, KeyPackageIn, LastResortExtension,
    ProtocolVersion,
};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::OpenMlsProvider;

const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

/// The exact message `KeyPackageVerifyError::UnsupportedExtension` renders.
/// Asserting on it keeps a "rejected" result honest: the package must be
/// refused by the capability check, not by a signature or lifetime failure.
const UNSUPPORTED: &str = "A key package extension is not supported in the leaf's capabilities.";

/// Build a real KeyPackage carrying `kp_extensions`, whose leaf declares
/// `declared` in its capabilities, then validate it the way production does:
/// serialize to wire bytes, deserialize into `KeyPackageIn`, and `validate`.
fn validate_key_package_with(
    kp_extensions: Vec<Extension>,
    declared: Vec<ExtensionType>,
) -> Result<(), String> {
    let provider = OpenMlsRustCrypto::default();
    let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).expect("signer");
    let credential = BasicCredential::new(b"grease-regression".to_vec());

    let bundle = KeyPackage::builder()
        .key_package_extensions(
            Extensions::<KeyPackage>::from_vec(kp_extensions).expect("key package extension list"),
        )
        .leaf_node_capabilities(Capabilities::builder().extensions(declared).build())
        .build(
            CIPHERSUITE,
            &provider,
            &signer,
            CredentialWithKey {
                credential: credential.into(),
                signature_key: signer.to_public_vec().into(),
            },
        )
        .expect("the KeyPackage must build; these inputs are all well formed");

    let wire = bundle
        .key_package()
        .tls_serialize_detached()
        .expect("serialize");
    let parsed = KeyPackageIn::tls_deserialize(&mut &wire[..]).expect("deserialize");

    parsed
        .validate(provider.crypto(), ProtocolVersion::Mls10)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// 1. A GREASE KeyPackage extension whose ID the leaf declares must validate.
///
/// This is the decisive second-site case and fails before the fix: the leaf
/// declares `Grease(v)` while the used extension reports `Unknown(v)`.
/// Covers all 15 RFC 9420 13.5 GREASE values.
#[test]
fn kp_grease_extension_declared_is_accepted() {
    for &value in &GREASE_VALUES {
        let result = validate_key_package_with(
            vec![grease_extension(value)],
            vec![ExtensionType::Grease(value)],
        );
        assert!(
            result.is_ok(),
            "GREASE 0x{value:04x} is declared in the leaf capabilities and used as a \
             KeyPackage extension; validation must accept it, got: {result:?}"
        );
    }
}

/// 2. An UNDECLARED GREASE KeyPackage extension must be IGNORED, not refused.
///
/// RFC 9420 s10: "unknown extensions in KeyPackage.extensions MUST be ignored,
/// and the creator of a KeyPackage object SHOULD include some random GREASE
/// extensions to help ensure that other clients correctly ignore unknown
/// extensions." s13.5 lists KeyPackage.extensions among the fields a sender
/// SHOULD fill with GREASE, and its note requires reflection into capabilities
/// only for LeafNode.extensions.
///
/// This is the row that rejected roughly seven in eight real mlspp KeyPackages:
/// mlspp picks the KeyPackage GREASE id independently of the capability list.
#[test]
fn kp_grease_extension_undeclared_is_ignored() {
    for &value in &GREASE_VALUES {
        let result = validate_key_package_with(vec![grease_extension(value)], vec![]);
        assert!(
            result.is_ok(),
            "GREASE 0x{value:04x} in KeyPackage.extensions is unknown to us and undeclared;              RFC 9420 s13.4 requires it to be ignored, got: {result:?}"
        );
    }
}

/// 3. Declaring a different GREASE ID changes nothing: the extension is unknown
///    either way, so it is ignored either way. No special GREASE rule is applied
///    - s13.5 forbids exactly that.
#[test]
fn kp_grease_extension_with_other_grease_declared_is_ignored() {
    for (i, &value) in GREASE_VALUES.iter().enumerate() {
        let other = GREASE_VALUES[(i + 1) % GREASE_VALUES.len()];
        assert_ne!(value, other);
        let result = validate_key_package_with(
            vec![grease_extension(value)],
            vec![ExtensionType::Grease(other)],
        );
        assert!(
            result.is_ok(),
            "0x{value:04x} used, 0x{other:04x} declared: still an unknown KeyPackage              extension, so still ignored, got: {result:?}"
        );
    }
}

/// 4. An ordinary unknown KeyPackage extension that is declared is accepted -
///    behaviour that must not change.
#[test]
fn kp_plain_unknown_extension_declared_is_accepted() {
    assert!(!is_grease_value(PLAIN_UNKNOWN));
    let result = validate_key_package_with(
        vec![Extension::Unknown(PLAIN_UNKNOWN, UnknownExtension(vec![7]))],
        vec![ExtensionType::Unknown(PLAIN_UNKNOWN)],
    );
    assert!(result.is_ok(), "declared unknown extension: {result:?}");
}

/// 5. An undeclared ORDINARY unknown KeyPackage extension is ignored too.
///
/// This is what keeps the rule general. s13.4 says "all unknown extensions",
/// not "all GREASE extensions", and s13.5 forbids special processing rules for
/// GREASE - so GREASE is ignored here by virtue of being unknown, never by
/// being GREASE. A GREASE-only carve-out would fail this test.
#[test]
fn kp_plain_unknown_extension_undeclared_is_ignored() {
    assert!(!is_grease_value(PLAIN_UNKNOWN));
    let result = validate_key_package_with(
        vec![Extension::Unknown(PLAIN_UNKNOWN, UnknownExtension(vec![7]))],
        vec![],
    );
    assert!(
        result.is_ok(),
        "an unknown KeyPackage extension is ignored whether or not it is GREASE: {result:?}"
    );
}

/// The asymmetry this whole distinction rests on, pinned in one place.
///
/// The SAME undeclared GREASE id is refused in `LeafNode.extensions` and ignored
/// in `KeyPackage.extensions`. Those are different fields with different rules:
/// s7.3 requires leaf extensions and capabilities to be consistent (and the
/// s13.5 note says GREASE added to LeafNode.extensions "need to be reflected in
/// LeafNode.capabilities.extensions" for exactly that reason), while s13.4
/// requires unknown KeyPackage extensions to be ignored.
#[test]
fn leaf_and_key_package_treat_an_undeclared_grease_extension_differently() {
    for &value in &GREASE_VALUES {
        assert!(
            !capabilities_cover(vec![], vec![grease_extension(value)]),
            "LeafNode: undeclared GREASE 0x{value:04x} must be refused (s7.3)"
        );
        assert!(
            validate_key_package_with(vec![grease_extension(value)], vec![]).is_ok(),
            "KeyPackage: the same undeclared GREASE 0x{value:04x} must be ignored (s13.4)"
        );
    }
}

/// 6. Non-GREASE typed extensions are unaffected in both directions.
///
/// `last_resort` is used rather than a default extension such as
/// `application_id` because the KeyPackage extension validator admits only
/// `LastResort` and `Unknown` - asserted here so the reason is on record.
#[test]
fn kp_last_resort_extension_behaviour_is_unchanged() {
    assert!(
        Extensions::<KeyPackage>::from_vec(vec![Extension::ApplicationId(
            ApplicationIdExtension::new(b"x")
        )])
        .is_err(),
        "a default extension is not permitted among key package extensions"
    );

    let declared = validate_key_package_with(
        vec![Extension::LastResort(LastResortExtension::new())],
        vec![ExtensionType::LastResort],
    );
    assert!(declared.is_ok(), "declared last_resort: {declared:?}");

    let undeclared = validate_key_package_with(
        vec![Extension::LastResort(LastResortExtension::new())],
        vec![],
    )
    .expect_err("undeclared last_resort must be rejected");
    assert_eq!(undeclared, UNSUPPORTED);
}

/// An empty KeyPackage extension list validates regardless of capabilities -
/// the control case proving these fixtures fail for the extension check alone
/// and not for some unrelated defect in how they are built.
#[test]
fn kp_with_no_extensions_always_validates() {
    assert!(validate_key_package_with(vec![], vec![]).is_ok());
    assert!(validate_key_package_with(vec![], vec![ExtensionType::Grease(0x0a0a)]).is_ok());
}
