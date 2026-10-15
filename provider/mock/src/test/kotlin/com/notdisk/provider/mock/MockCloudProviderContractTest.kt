package com.notdisk.provider.mock

import com.notdisk.core.provider.testing.ProviderContractRules

/**
 * Runs the project-wide provider contract suite against the Mock provider.
 *
 * This is the reference run: if the shared contract rules are satisfiable at all, they must be
 * satisfiable offline. A failure here means the contract itself (or the Mock) is wrong, and the
 * real 115 provider would then be validated against a broken specification.
 */
class MockCloudProviderContractTest : ProviderContractRules(MockProviders.contractFixture())
