package dev.ngb.tenant.billing.adapter.client.feign;

import dev.ngb.core.feign.FeignClients;
import dev.ngb.core.feign.FeignJson;
import dev.ngb.core.feign.FeignSettings;
import dev.ngb.core.feign.TokenProvider;

/** Builds one Feign client for one token provider, so the bootstrap and the tenant never share a proxy. */
public final class BillingFeignClientFactory {

    private BillingFeignClientFactory() {}

    public static FeignBillingClient create(FeignSettings settings, TokenProvider tokens) {
        BillingFeignApi api = FeignClients.create(
                BillingFeignApi.class,
                settings,
                tokens,
                TenantFeignFailures.INSTANCE,
                new BillingErrorDecoder(FeignJson.mapper()));
        return new FeignBillingClient(api);
    }
}
