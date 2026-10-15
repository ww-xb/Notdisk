package com.notdisk.domain.capability

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.domain.failureOfType
import com.notdisk.domain.requireSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [CapabilityGate].
 *
 * ### Why the gate's three-state policy is worth testing on its own
 * The gate is the single place where the difference between
 * [CapabilitySupport.UNVERIFIED] ("the vendor documents it, we have not observed it") and
 * `UNSUPPORTED` ("do not offer it") is turned into behaviour. Every use case in the module depends
 * on that decision being made the same way, so a regression here would silently change the
 * behaviour of ~20 call sites at once — and in both directions it would be bad:
 *
 *  - if `UNVERIFIED` started being refused, every documented-but-unexercised endpoint becomes
 *    permanently unreachable and the feature dies quietly;
 *  - if `UNSUPPORTED` started being allowed, the app begins sending requests that vendors are
 *    documented to reject, and the user sees opaque vendor errors instead of an honest refusal.
 *
 * These tests pin both directions, plus the parts of the gate's contract that are easy to get
 * subtly wrong: the capability path must reach the error (so a failure is traceable to the exact
 * declaration that refused it), and [CapabilityGate.requireVerified] must be strictly stricter.
 */
class CapabilityGateTest {

    private val gate = CapabilityGate.Default

    // ---------------------------------------------------------------------------------------------
    // require
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `require passes a SUPPORTED capability`() {
        assertTrue(gate.require(CapabilitySupport.SUPPORTED, "operations.rename").isSuccess())
    }

    @Test
    fun `require passes an UNVERIFIED capability`() {
        // Load-bearing: refusing UNVERIFIED would lock out every endpoint the build has not yet
        // exercised against a live vendor, which is most of them for a new integration.
        assertTrue(
            gate.require(CapabilitySupport.UNVERIFIED, "operations.share").isSuccess(),
            "UNVERIFIED means unobserved, not impossible",
        )
    }

    @Test
    fun `require refuses an UNSUPPORTED capability with a typed Unsupported`() {
        val result = gate.require(CapabilitySupport.UNSUPPORTED, "operations.copy")

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals("operations.copy", error.capability)
    }

    @Test
    fun `require carries a custom message so the remedy is specific`() {
        val result = gate.require(
            support = CapabilitySupport.UNSUPPORTED,
            capability = "operations.trash.purge",
            message = "该网盘不支持永久删除",
        )

        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals("该网盘不支持永久删除", error.message)
        assertEquals("operations.trash.purge", error.capability)
    }

    @Test
    fun `require defaults to the shared unsupported message`() {
        val error = gate.require(CapabilitySupport.UNSUPPORTED, "x")
            .failureOfType<CloudError.Unsupported>()

        assertEquals(CapabilityGate.DEFAULT_UNSUPPORTED_MESSAGE, error.message)
    }

    @Test
    fun `the refusal carries no HTTP status, because nothing left the device`() {
        val error = gate.require(CapabilitySupport.UNSUPPORTED, "x")
            .failureOfType<CloudError.Unsupported>()

        // A status would send a post-mortem hunting for a request that was never sent.
        assertEquals(null, error.httpStatus)
        assertEquals(null, error.vendorCode)
    }

    // ---------------------------------------------------------------------------------------------
    // requireVerified
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `requireVerified accepts only a verified SUPPORTED capability`() {
        assertTrue(gate.requireVerified(CapabilitySupport.SUPPORTED, "x").isSuccess())
        assertTrue(gate.requireVerified(CapabilitySupport.UNVERIFIED, "x").isFailure())
        assertTrue(gate.requireVerified(CapabilitySupport.UNSUPPORTED, "x").isFailure())
    }

    @Test
    fun `requireVerified is strictly stricter than require`() {
        // Guards the distinction between the two: if requireVerified ever loosened to match
        // require, an irreversible operation could start being attempted on an unverified
        // endpoint without any test noticing.
        val unverified = CapabilitySupport.UNVERIFIED

        assertTrue(gate.require(unverified, "x").isSuccess())
        assertTrue(gate.requireVerified(unverified, "x").isFailure())
    }

    // ---------------------------------------------------------------------------------------------
    // requireFlag
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `requireFlag maps a boolean capability onto the same failure shape`() {
        assertTrue(gate.requireFlag(available = true, capability = "media.separateAudioTracks").isSuccess())

        val error = gate.requireFlag(available = false, capability = "media.separateAudioTracks")
            .failureOfType<CloudError.Unsupported>()
        assertEquals("media.separateAudioTracks", error.capability)
    }

    // ---------------------------------------------------------------------------------------------
    // UI description mapping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every capability state maps to a distinct user-facing description`() {
        val descriptions = CapabilitySupport.entries.map { it.toUiDescription() }

        assertEquals(
            CapabilitySupport.entries.size,
            descriptions.toSet().size,
            "collapsing two states into the same wording is exactly the dishonesty the enum exists " +
                "to prevent",
        )
        assertTrue(descriptions.none { it.isBlank() })
    }

    @Test
    fun `UNVERIFIED wording states the uncertainty rather than promising the feature`() {
        val text = CapabilitySupport.UNVERIFIED.toUiDescription()

        assertTrue(
            text.contains("未验证") || text.contains("可能"),
            "the UI must be able to hedge; got: $text",
        )
    }

    @Test
    fun `isOfferableToUser hides only the genuinely unsupported state`() {
        assertTrue(CapabilitySupport.SUPPORTED.isOfferableToUser)
        assertTrue(
            CapabilitySupport.UNVERIFIED.isOfferableToUser,
            "hiding it would make an unverified endpoint permanently unreachable",
        )
        assertFalse(CapabilitySupport.UNSUPPORTED.isOfferableToUser)
    }

    @Test
    fun `the gate is stateless so one shared instance is safe`() {
        // Two calls with the same input must produce the same outcome, and the shared Default
        // instance must not accumulate anything between them.
        repeat(3) {
            assertTrue(gate.require(CapabilitySupport.SUPPORTED, "x").isSuccess())
            assertEquals(
                "x",
                gate.require(CapabilitySupport.UNSUPPORTED, "x")
                    .failureOfType<CloudError.Unsupported>()
                    .capability,
            )
        }
    }
}
