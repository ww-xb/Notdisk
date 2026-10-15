package com.notdisk.provider.baidu

import com.notdisk.core.provider.testing.ProviderContractRules

/**
 * Runs the project-wide provider contract suite against the real Baidu Netdisk provider.
 *
 * ### How a network-dependent provider satisfies an offline contract suite
 * The suite is written against the unified model and asserts *behavioural* rules (typed failures,
 * terminating pagination, honest capability declarations, no URL inside a `PreviewKind`, …). It never
 * asserts a vendor's own semantics. A real provider can therefore satisfy it either against a live
 * account or, as here, against a recorded-response server.
 *
 * [BaiduContractFixture] pins the responses the suite's probes need, taken verbatim from the reference
 * material wherever a measured body exists. The suite therefore exercises the same envelope parsing
 * and mapping the production path uses.
 *
 * ### What this class deliberately does not claim
 * It does not prove that Baidu's service behaves as documented today; that requires a live account and
 * is tracked in `docs/api-mapping.md`'s "待验证" table. It proves that *given the documented payloads*,
 * the provider honours the shared contract — which is the property the architecture depends on.
 */
class BaiduProviderContractTest : ProviderContractRules(BaiduContractFixture.build())
