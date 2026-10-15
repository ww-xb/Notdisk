package com.notdisk.provider.p115

import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.provider.testing.ProviderContractFixture
import com.notdisk.core.provider.testing.ProviderContractRules

/**
 * Runs the project-wide provider contract suite against the real 115 provider.
 *
 * ### How a network-dependent provider satisfies an offline contract suite
 * The suite is written against the unified model and asserts *behavioural* rules (typed failures,
 * terminating pagination, honest capability declarations, no URL inside a `PreviewKind`, …). It never
 * asserts a vendor's own semantics. A real provider can therefore satisfy it either against a live
 * account or, as here, against a recorded-response server.
 *
 * This class pins the responses that the suite's probes need. They are the **verbatim** bodies from
 * `/data/workspace/115_reverse/api_bodies.json` wherever one exists, so the suite is exercising the
 * payload shapes 115 really returns.
 *
 * ### What this class deliberately does not claim
 * It does not prove that 115's service behaves as documented today; that requires a live account and
 * is tracked in `docs/api-mapping.md`'s "待验证" table. It proves that *given the documented
 * payloads*, the provider honours the shared contract — which is the property the architecture
 * depends on.
 */
class P115ProviderContractTest : ProviderContractRules(P115ContractFixture.build())
