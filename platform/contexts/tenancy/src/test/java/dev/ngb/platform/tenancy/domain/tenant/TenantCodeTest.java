package dev.ngb.platform.tenancy.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The tenant code is a URL-safe subdomain label, three to forty characters, starting with a letter. */
class TenantCodeTest {

    @Test
    void aValidCodeIsTheStringForm() {
        assertThat(new TenantCode("acme").value()).isEqualTo("acme");
        assertThat(new TenantCode("acme-2")).hasToString("acme-2");
    }

    @Test
    void theShapeIsEnforced() {
        assertThatThrownBy(() -> new TenantCode("ab")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantCode("1acme")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantCode("acme_1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantCode("acme.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantCode("acme-")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantCode("Acme")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantCode("a".repeat(41))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewTenantMayNotTakeTheReservedPlatformCode() {
        assertThat(TenantCode.PLATFORM).isEqualTo("platform");
        assertThat(TenantCode.forNewTenant("acme").value()).isEqualTo("acme");
        assertThatThrownBy(() -> TenantCode.forNewTenant(TenantCode.PLATFORM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }
}
