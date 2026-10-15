package com.notdisk.provider.mock

/**
 * Builds a Mock provider and its contract fixture **as one unit**.
 *
 * The subtlety this helper exists to prevent: a fixture must reference an account that belongs to
 * the *same* provider instance under test. Constructing the provider twice — once for the fixture
 * and once for the assertion — yields a fixture whose account is unknown to the provider, and the
 * contract suite then fails for a reason that has nothing to do with the provider.
 */
internal object MockProviders {

    fun contractFixture(
        latencyMs: Long = 0,
    ): MockContractFixture {
        val provider = MockCloudProvider(latencyMs = latencyMs, seedDemoAccount = true)
        val accountId = requireNotNull(provider.demoAccountId) {
            "MockCloudProvider(seedDemoAccount = true) must expose a demo account"
        }
        return MockContractFixture(provider = provider, accountId = accountId)
    }

    /** A provider with no accounts bound, for testing the "not logged in" paths. */
    fun empty(): MockCloudProvider = MockCloudProvider(seedDemoAccount = false)
}
