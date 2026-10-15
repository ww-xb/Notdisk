package com.notdisk.domain.testing

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.AuthProvider
import com.notdisk.core.provider.CloudProvider
import com.notdisk.core.provider.FileOperationProvider
import com.notdisk.core.provider.FileSystemProvider
import com.notdisk.core.provider.MediaProvider
import com.notdisk.core.provider.PreviewProvider

/**
 * A **hand-written, configurable fake `CloudProvider`** for `core:domain` tests.
 *
 * ### Why not mockk
 * This module's tests are about the *registry path*: that a use case resolves a provider by id,
 * reads its declaration, and only then delegates. Mocking `CloudProvider` would undermine exactly
 * that. A `mockk(relaxed = true)` provider answers every method successfully, so a use case that
 * called the *wrong* method would still pass — the test would be verifying its own arrangement
 * rather than the production code.
 *
 * A hand-written fake forces a different discipline: every method has a concrete default, every
 * call is recorded, and tests assert on the recorded log. That is what makes this module's central
 * property checkable: **"the provider was called zero times"**. A relaxed mock cannot express it.
 *
 * ### What it deliberately does not do
 * It simulates no latency, no pagination state and no file system. Those belong to the provider
 * contract suite in `core:provider-testing`, which runs against real provider implementations. This
 * fake exists only to make the domain layer's *orchestration* observable.
 */
class FakeCloudProvider(
    override val id: ProviderId,
    override val displayName: String = "Fake $id",
    /** A `var` so a test can flip one declaration and assert the resulting refusal. */
    var capabilityDeclaration: CloudCapabilities = CapabilityFixtures.allSupported(id),
) : CloudProvider {

    /** Every call this fake received, in order, across every surface. */
    val calls: MutableList<ProviderCall> = mutableListOf()

    override val auth: AuthProvider = FakeAuthProvider(this)
    override val files: FileSystemProvider = FakeFileSystemProvider(this)
    override val operations: FileOperationProvider = FakeFileOperationProvider(this)
    override val media: MediaProvider = FakeMediaProvider(this)
    override val preview: PreviewProvider = FakePreviewProvider(this)

    /** Typed accessors, so a test configures a surface without an unchecked cast. */
    val authFake: FakeAuthProvider get() = auth as FakeAuthProvider
    val filesFake: FakeFileSystemProvider get() = files as FakeFileSystemProvider
    val operationsFake: FakeFileOperationProvider get() = operations as FakeFileOperationProvider
    val mediaFake: FakeMediaProvider get() = media as FakeMediaProvider
    val previewFake: FakePreviewProvider get() = preview as FakePreviewProvider

    override fun capabilities(): CloudCapabilities = capabilityDeclaration

    internal fun record(surface: ProviderSurface, method: String, vararg args: Pair<String, Any?>) {
        calls += ProviderCall(surface = surface, method = method, namedArgs = args.toMap())
    }

    /** Total provider calls of any kind. The "zero calls" assertion uses this. */
    fun totalCalls(): Int = calls.size

    /** `true` when this provider was never touched — the core gating assertion. */
    fun wasNeverCalled(): Boolean = calls.isEmpty()

    /** Every recorded call on one surface, for a targeted assertion. */
    fun callsOn(surface: ProviderSurface): List<ProviderCall> =
        calls.filter { it.surface == surface }

    /** Clears the log so a second phase of a test can assert independently of the first. */
    fun resetCalls() {
        calls.clear()
    }
}

/** Which sub-interface a recorded call went to. */
enum class ProviderSurface { AUTH, FILES, OPERATIONS, MEDIA, PREVIEW }

/**
 * One recorded provider call: the surface, the method, and the arguments that identify it.
 *
 * [namedArgs] is a plain map rather than typed fields because the assertion it supports is almost
 * always "the provider was reached with *this* value", and one uniform lookup across five
 * sub-interfaces beats five argument types. Reference arguments are stored by reference; tests
 * compare them with equality.
 */
data class ProviderCall(
    val surface: ProviderSurface,
    val method: String,
    val namedArgs: Map<String, Any?> = emptyMap(),
) {
    operator fun get(key: String): Any? = namedArgs[key]

    override fun toString(): String = "$surface.$method($namedArgs)"
}

/** Thrown by a fake configured to violate the no-throw contract on purpose. */
class FakeProviderException(message: String) : RuntimeException(message)

/** A typed `Unsupported` failure, for fakes configured to refuse. */
internal fun <T> unsupported(capability: String): CloudResult<T> = CloudResult.Failure(
    CloudError.Unsupported(message = "fake: $capability unsupported", capability = capability),
)
