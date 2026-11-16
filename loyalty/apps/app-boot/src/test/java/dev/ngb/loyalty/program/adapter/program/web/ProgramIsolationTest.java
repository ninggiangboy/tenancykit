package dev.ngb.loyalty.program.adapter.program.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.LoyaltyApiTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Row-level security, seen from the API: a second tenant reads none of the first tenant's program, tier list or
 * promotions, and cannot change them through an id it guessed.
 */
class ProgramIsolationTest extends LoyaltyApiTest {

    @Autowired ProgramReader reader;

    @Test
    void anotherTenantReadsNoneOfTheProgramTiersOrPromotions() {
        TenantId owner = configured("USD");
        ApiResponse tier = call(
                owner,
                put("/api/v1/tiers").header("If-Match", "*").contentType(MediaType.APPLICATION_JSON).content("""
                        {"tiers": [{"code": "BRONZE", "name": "Bronze", "minSpend": 0, "earnMultiplier": 1.00}]}
                        """),
                LoyaltyPermissions.PROGRAM_MANAGE);
        assertThat(tier.status()).isEqualTo(200);
        String promotionId = call(
                owner,
                post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Owner only", "multiplier": 2.00}
                        """),
                LoyaltyPermissions.PROGRAM_MANAGE).json().get("promotionId").asString();

        TenantId other = new TenantId(UUID.randomUUID());

        assertThat(call(other, get("/api/v1/program"), LoyaltyPermissions.PROGRAM_READ).status())
                .isEqualTo(404);
        assertThat(call(other, get("/api/v1/tiers"), LoyaltyPermissions.PROGRAM_READ).status())
                .isEqualTo(404);
        assertThat(call(other, get("/api/v1/promotions"), LoyaltyPermissions.PROGRAM_READ).json().get("data").size())
                .as("the catalog of another tenant is empty")
                .isZero();
        ApiResponse foreign = call(
                other, get("/api/v1/promotions/" + promotionId), LoyaltyPermissions.PROGRAM_READ);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.code()).isEqualTo("PROMOTION_NOT_FOUND");

        assertThat(inTenant(other, reader::program)).isEmpty();
        assertThat(inTenant(other, reader::tiers)).isEmpty();
        assertThat(inTenant(other, () -> reader.activePromotionsAt(Instant.parse("2026-01-05T00:00:00Z"))))
                .isEmpty();
    }

    @Test
    void anotherTenantCannotReplaceTheOwnersTiersOrPatchItsPromotion() {
        TenantId owner = configured("USD");
        call(
                owner,
                put("/api/v1/tiers").header("If-Match", "*").contentType(MediaType.APPLICATION_JSON).content("""
                        {"tiers": [{"code": "BRONZE", "name": "Bronze", "minSpend": 0, "earnMultiplier": 1.00}]}
                        """),
                LoyaltyPermissions.PROGRAM_MANAGE);
        String promotionId = call(
                owner,
                post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Owner only", "multiplier": 2.00}
                        """),
                LoyaltyPermissions.PROGRAM_MANAGE).json().get("promotionId").asString();

        TenantId other = new TenantId(UUID.randomUUID());

        ApiResponse tiers = call(
                other,
                put("/api/v1/tiers").header("If-Match", "*").contentType(MediaType.APPLICATION_JSON).content("""
                        {"tiers": [{"code": "OTHER", "name": "Other", "minSpend": 0, "earnMultiplier": 1.00}]}
                        """),
                LoyaltyPermissions.PROGRAM_MANAGE);
        ApiResponse promotion = call(
                other,
                patch("/api/v1/promotions/" + promotionId).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"active": false}
                                """),
                LoyaltyPermissions.PROGRAM_MANAGE);

        assertThat(tiers.status()).isEqualTo(404);
        assertThat(tiers.code()).isEqualTo("PROGRAM_NOT_CONFIGURED");
        assertThat(promotion.status()).isEqualTo(404);
        assertThat(promotion.code()).isEqualTo("PROMOTION_NOT_FOUND");
    }

    @Test
    void eachTenantKeepsItsOwnProgram() {
        TenantId first = configured("USD");
        TenantId second = configured("EUR");

        assertThat(call(first, get("/api/v1/program"), LoyaltyPermissions.PROGRAM_READ)
                        .json().get("currency").asString())
                .isEqualTo("USD");
        assertThat(call(second, get("/api/v1/program"), LoyaltyPermissions.PROGRAM_READ)
                        .json().get("currency").asString())
                .isEqualTo("EUR");
    }

    private TenantId configured(String currency) {
        TenantId tenant = new TenantId(UUID.randomUUID());
        ApiResponse configured = call(
                tenant,
                put("/api/v1/program").header("If-Match", "*").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency": "%s", "currencyUnit": 1000}
                                """.formatted(currency)),
                LoyaltyPermissions.PROGRAM_MANAGE);
        assertThat(configured.status()).isEqualTo(200);
        return tenant;
    }
}
