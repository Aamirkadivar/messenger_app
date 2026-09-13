package com.messenger.app.data.model

import com.messenger.app.di.AppModule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 68A - the wire contract of POST /e2ee/devices, pinned against the PRODUCTION serializer.
 *
 * Phase 67 declared `authorityKind = "device"` as a Kotlin default. The production `Json` does not
 * encode defaults, so the field never reached the wire, and on real hardware the server - correctly -
 * treated every Phase 67 Android enrolment as legacy and refused it once the account was retired.
 * 661 unit tests passed throughout, because none of them serialised this model with the real
 * configuration.
 *
 * So these tests deliberately do NOT build their own `Json { ... }`. They use
 * [AppModule.provideJson] and [AppModule.provideRetrofit] - the exact objects Hilt injects - so a
 * future change to either shows up here rather than on a phone.
 */
class DeviceRegisterWireContractTest {

    private val json: Json = AppModule.provideJson()

    private fun request(
        challengeId: String = "__CHALLENGE_ID__",
        proofB64: String = "__PROOF_B64__",
    ) = E2EEDeviceRegisterRequest(
        deviceId = "__DEVICE_ID__",
        name = "__NAME__",
        platform = "android",
        publicKey = "__PUBLIC_KEY__",
        challengeId = challengeId,
        proofB64 = proofB64,
        // authorityKind deliberately NOT passed: production (E2EEVaultRepository.registerDevice)
        // relies on the default, and the default is exactly what went missing.
    )

    /** What Retrofit actually writes to the socket, through the production converter factory. */
    private fun retrofitBody(value: Any): String {
        val retrofit = AppModule.provideRetrofit(OkHttpClient(), json)
        val converter = retrofit.requestBodyConverter<Any>(value.javaClass, emptyArray(), emptyArray())
        val buffer = Buffer()
        converter.convert(value)!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    // ------------------------------------------------------------------ §3 the defect, pinned

    @Test
    fun authorityKindIsOnTheWireThroughTheProductionJson() {
        val body = json.encodeToString(request())
        val field = json.parseToJsonElement(body).jsonObject["authority_kind"]

        assertNotNull("authority_kind is missing from the serialized request: $body", field)
        assertEquals("device", (field as JsonPrimitive).content)
        assertTrue(body.contains("\"authority_kind\":\"device\""))
    }

    @Test
    fun authorityKindIsOnTheWireThroughTheProductionRetrofitConverter() {
        val body = retrofitBody(request())
        val field = json.parseToJsonElement(body).jsonObject["authority_kind"]

        assertNotNull("Retrofit's converter dropped authority_kind: $body", field)
        assertEquals("device", (field as JsonPrimitive).content)
    }

    /**
     * The exact bytes, field order included. The backend suite pins the SAME literal
     * (TestP68A_AndroidWireBodyIsAcceptedByTheBackend), so the two sides cannot drift apart
     * silently: change the Android encoding and this fails; change the literal and the Go test no
     * longer matches what Android sends.
     */
    @Test
    fun exactRegistrationWireBody() {
        assertEquals(
            """{"device_id":"__DEVICE_ID__","name":"__NAME__","platform":"android",""" +
                """"public_key":"__PUBLIC_KEY__","challenge_id":"__CHALLENGE_ID__",""" +
                """"proof_b64":"__PROOF_B64__","authority_kind":"device"}""",
            retrofitBody(request()),
        )
    }

    // ------------------------------------------------------------------ §4 nothing else moved

    /** The fix is on one field. The shared configuration must still skip defaults. */
    @Test
    fun productionJsonStillDoesNotEncodeDefaultsGlobally() {
        assertFalse(
            "encodeDefaults was switched on globally - that changes ~250 other request fields",
            json.configuration.encodeDefaults,
        )
    }

    /**
     * The two OTHER default-valued fields on this very DTO keep their Phase 44 behaviour: when left at
     * "" they are omitted, exactly as before. Only authority_kind changed.
     */
    @Test
    fun otherDefaultValuedFieldsOnTheSameDtoAreStillOmitted() {
        val obj: JsonObject = json.parseToJsonElement(
            retrofitBody(request(challengeId = "", proofB64 = ""))
        ).jsonObject

        assertFalse("challenge_id started being encoded at its default", "challenge_id" in obj)
        assertFalse("proof_b64 started being encoded at its default", "proof_b64" in obj)
        assertEquals("device", (obj["authority_kind"] as JsonPrimitive).content)
    }

    /**
     * An unrelated request DTO with defaults: a vault CREATE still omits expected_version=0 and
     * protocol_version=1, byte for byte as before. A global encodeDefaults would have added both.
     */
    @Test
    fun unrelatedVaultPutWireContractIsUnchanged() {
        val body = retrofitBody(
            E2EEVaultPutRequest(
                vaultVersion = 1,
                suite = "s",
                vaultCiphertextB64 = "c",
                pwKdf = "k",
                pwSaltB64 = "a",
                pwParams = "{}",
                pwWrappedMasterB64 = "w",
            )
        )
        assertEquals(
            """{"vault_version":1,"suite":"s","vault_ciphertext_b64":"c","pw_kdf":"k",""" +
                """"pw_salt_b64":"a","pw_params":"{}","pw_wrapped_master_b64":"w"}""",
            body,
        )
    }

    /** The challenge request has no defaults and must be untouched. */
    @Test
    fun challengeRequestWireContractIsUnchanged() {
        assertEquals(
            """{"device_id":"d","public_key":"p"}""",
            retrofitBody(E2EEDeviceChallengeRequest(deviceId = "d", publicKey = "p")),
        )
    }
}
