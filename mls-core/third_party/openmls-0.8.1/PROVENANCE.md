# OpenMLS 0.8.1 — local copy with three corrected defects

Upstream: https://github.com/openmls/openmls/
Licence:  MIT (see `license = "MIT"` in `Cargo.toml`)
Version:  0.8.1, copied verbatim from the crates.io package

Registry package SHA-256 (matches `mls-core/Cargo.lock`):

    dcb512bfe6a55777518853ea535c6241f069cb0e8984678c117151d2a1e7e903

`src/` is byte-identical to the published crate apart from three corrections,
in `src/treesync/node/leaf_node/capabilities.rs`,
`src/treesync/node/leaf_node.rs`, and `src/key_packages/key_package_in.rs`
(all documented under "The fixes").
The only other additions to this directory are this file and the removal of
Cargo's internal `.cargo-ok` extraction marker. It is wired in through
`[patch.crates-io]` in `mls-core/Cargo.toml`, so the build compiles this source
rather than the registry copy. The version is NOT bumped: this is 0.8.1 with one
defect corrected, not an upgrade.

## The fixes

### Site 1 — a leaf's own extensions vs its capabilities

`src/treesync/node/leaf_node/capabilities.rs`, `Capabilities::contains_extensions()`:

    -  .all(|e| e.is_default() || self.extensions().contains(&e))
    +  .all(|e| e.is_default() || self.extensions().contains(&ExtensionType::from(u16::from(e))))

RFC 9420 §7.3 validates the capability requirement by "checking that the ID for
each extension in the extensions field is listed in the capabilities.extensions
field" — by ID, with no GREASE exemption. Routing the used extension's type
through the crate's own canonical `From<u16>` conversion makes the comparison
happen by ID. That composition is the identity on every `ExtensionType` variant
except `Unknown(g)` for a GREASE `g`, which it maps to `Grease(g)` — so the
change affects GREASE values and nothing else.

Verified discriminating: against pristine 0.8.1 the two "GREASE used + declared"
regression tests fail and the other eight pass; with the fix all ten pass.

### Site 2 — KeyPackage / group-context extensions vs a leaf's capabilities

`src/treesync/node/leaf_node.rs`, `LeafNode::supports_extension()`:

    -  .contains(extension_type)
    +  .contains(&ExtensionType::from(u16::from(*extension_type)))

The same variant-vs-ID asymmetry, on a separate path. This one is reached from
`KeyPackageIn::validate` (KeyPackage extensions) and from five group-context
checks. It is what rejected the captured mlspp KeyPackage during
`validate_key_package`; `validate_locally` — and therefore site 1 — is never
called on that path, so the two fixes are independent.

A narrower justification applies here, and it is worth stating precisely rather
than overclaiming. RFC 9420 §7.2/§7.3 govern site 1 and require comparison by
extension ID. For the KeyPackage-extension path the RFC imposes **no** equivalent
declaration requirement: §10.1 lists no such check, and §13.4 says an
implementation that does not understand an extension type MUST ignore it. OpenMLS
is therefore stricter than the RFC here. The justification for normalising this
site is that comparing by numeric ID is internally consistent with how OpenMLS
represents GREASE, and that it leaves every non-GREASE outcome unchanged — not
that the RFC mandates this particular check.

Verified discriminating: against the site-1-only tree the "GREASE key package
extension declared" regression fails and the other sixteen pass; with both fixes
all seventeen pass.

### Site 3 — unknown KeyPackage extensions must be ignored

`src/key_packages/key_package_in.rs`, `KeyPackageIn::validate()`.

Upstream required every extension in `KeyPackage.extensions` to be listed in
`leaf_node.capabilities.extensions`. That is a requirement RFC 9420 s10 places on
the KeyPackage's *creator*; the receiver's obligations run the other way:

* s10   - "unknown extensions in KeyPackage.extensions MUST be ignored, and the
          creator of a KeyPackage object SHOULD include some random GREASE
          extensions to help ensure that other clients correctly ignore unknown
          extensions."
* s10.1 - the receiver-side validation list has four bullets, none about
          extensions or capabilities.
* s13.4 - "A client processing a KeyPackage object MUST ignore ... all unknown
          extensions in the extensions and leaf_node.extensions fields."
* s13.5 - lists `KeyPackage.extensions` among the fields a sender SHOULD fill
          with GREASE, and its note requires reflection into capabilities only
          for `LeafNode.extensions`, "since the LeafNode validation process
          described in Section 7.3 requires that these two fields be consistent".

Unknown extension types are now skipped as a class. GREASE is not named: s13.5
says clients "MUST NOT implement any special processing rules" for GREASE
values, so GREASE is ignored here only by virtue of being unknown. Recognised
types stay bound to s10, so an undeclared `last_resort` is still refused, and
`leaf_node.extensions` are untouched - they remain checked against capabilities
by `LeafNode::validate_locally`, as s7.3 requires.

Measured effect on real Windows output: 8 KeyPackages generated by the actual
mlspp-backed Windows client went from 1/8 accepted to 8/8. mlspp's
`KeyPackage::KeyPackage()` greases `KeyPackage.extensions` without reflecting the
value into the leaf's capabilities, so before this change acceptance depended on
a chance collision between two independent random draws.

## Consequence for the captured mlspp fixture

With both fixes the real captured mlspp KeyPackage in `mls-core/tests/ds_contract.rs`
validates, and can be added to a group. It always should have: it declares the
GREASE ids it uses. That fixture is now a positive interoperability regression;
a separate, deliberately invalid package carries the negative batch-isolation
contract.

## The defect

A peer that declares a GREASE extension in `capabilities.extensions` and then
actually uses it is rejected:

    Leaf node does not support all extensions it uses
        Supported extensions: [Grease(39578), Grease(2570)]
        Used extensions:      [Unknown(39578, UnknownExtension(...))]

    => "staged welcome: Extensions are not acceptable."
    => "invalid key package: A key package extension is not supported in the
        leaf's capabilities"

`Capabilities::extensions` is a `Vec<ExtensionType>`, so a GREASE number in it
becomes `ExtensionType::Grease(v)` via `impl From<u16> for ExtensionType`. The
same number used as an actual extension becomes `Extension::Unknown(v, ..)`,
whose `extension_type()` is `ExtensionType::Unknown(v)`. Those are distinct
variants, so the membership test in
`Capabilities::contains_extensions()` (`treesync/node/leaf_node/capabilities.rs`)
never matches:

    extensions.iter().map(Extension::extension_type)
        .all(|e| e.is_default() || self.extensions().contains(&e))

This contradicts `src/grease.rs`, which documents that during capability
validation GREASE values are "treated the same as unknown values and filtered
out appropriately". No such filtering exists. RFC 9420 §13.5 exists precisely so
implementations do not reject values they do not recognise.

Reproduced through OpenMLS's own public GREASE injection in
`mls-core/tests/grease_extension_type.rs`
(`capabilities_do_not_contain_the_grease_extension_they_declare`).

## Why the first attempted fix was withdrawn

The first attempt changed `Extension::extension_type()` to report
`ExtensionType::Grease(v)` for GREASE values. That is **wrong**, and was
reverted. Upstream's `Unknown` there is deliberate:

* `src/extensions/codec.rs` collapses
  `ExtensionType::Grease(g) | ExtensionType::Unknown(g)` into
  `Extension::Unknown(g, ..)` when deserializing. `extension_type()` returning
  `Unknown(g)` is the exact inverse of that — a consistent round-trip.
* `ExtensionType::Grease(_)` is explicitly **invalid** inside a LeafNode
  (`is_valid_in_leaf_node` → `false`) and inside a KeyPackage (the validator
  admits only `LastResort` and `Unknown(_)`).

So reporting `Grease` made every GREASE-bearing LeafNode/KeyPackage fail during
*deserialization*, surfacing as the misleading
`DecodingError("Found duplicate extensions")` — the catch-all string that
`extensions/mod.rs`'s `.map_err(|_| ..)` applies to any `InvalidExtensionError`.
It moved the rejection earlier rather than removing it, and broke the pinned
contract in `mls-core/tests/ds_contract.rs` that a real captured mlspp
KeyPackage "must reach the validate step, not fail at deserialize".

The comparison in capability validation — not the extension's reported type —
is the inconsistent part.
