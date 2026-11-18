package dev.ngb.loyalty.program.adapter.tier.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.application.program.command.SaveProgramUseCase;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import dev.ngb.loyalty.program.application.program.port.ProgramRepository;
import dev.ngb.loyalty.program.application.tier.command.ReplaceTiersUseCase;
import dev.ngb.loyalty.program.application.tier.port.TierRepository;
import dev.ngb.loyalty.program.application.tier.service.TierRules;
import dev.ngb.loyalty.program.domain.program.Program;
import dev.ngb.loyalty.program.domain.tier.Tier;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.LoyaltyApiTest;
import dev.ngb.loyalty.support.PostgresContainerConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The tier list: it belongs to the program, a replace works by code and keeps an existing code's identity, and a list a
 * member could not climb is refused as a whole.
 */
class TierApiIntegrationTest extends LoyaltyApiTest {

    private static final String PROGRAM = """
            {"currency": "USD", "currencyUnit": 1000}
            """;

    private static final String TWO_TIERS = """
            {"tiers": [
              {"code": "BRONZE", "name": "Bronze", "minSpend": 0, "earnMultiplier": 1.00},
              {"code": "SILVER", "name": "Silver", "minSpend": 1000, "earnMultiplier": 1.50}
            ]}
            """;

    @Autowired SaveProgramUseCase save;
    @Autowired ProgramRepository programs;
    @Autowired TierRepository tiers;
    @Autowired ReplaceTiersUseCase replace;
    @Autowired UnitOfWork unitOfWork;
    @Autowired TenantContext context;
    @Autowired IdGenerator ids;
    @Autowired Clock clock;

    @Test
    void aTierListCannotExistBeforeTheProgram() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        ApiResponse response = putTiers(tenant, "*", TWO_TIERS);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("PROGRAM_NOT_CONFIGURED");
    }

    @Test
    void aValidListIsStoredInOrderAndMovesTheProgramVersion() {
        TenantId tenant = configured();

        ApiResponse response = putTiers(tenant, "\"1\"", TWO_TIERS);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.etag()).isEqualTo("\"2\"");
        assertThat(response.json().get("version").asLong()).isEqualTo(2);
        assertThat(response.json().get("tiers").size()).isEqualTo(2);
        assertThat(response.json().get("tiers").get(0).get("code").asString()).isEqualTo("BRONZE");
        assertThat(response.json().get("tiers").get(1).get("minSpend").asLong()).isEqualTo(1000);
        assertThat(response.json().get("tiers").get(1).get("earnMultiplier").decimalValue())
                .isEqualByComparingTo("1.50");

        ApiResponse read = call(tenant, get("/api/v1/tiers"), LoyaltyPermissions.PROGRAM_READ);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.etag()).isEqualTo("\"2\"");
        assertThat(read.json().get("tiers").get(0).get("name").asString()).isEqualTo("Bronze");
    }

    @Test
    void aReplaceByCodeKeepsTheIdentitiesOfTheCodesItKeeps() {
        TenantId tenant = configured();
        putTiers(tenant, "*", TWO_TIERS);
        UUID bronzeBefore = idOf(tenant, "BRONZE");

        ApiResponse replaced = putTiers(
                tenant,
                "*",
                """
                {"tiers": [
                  {"code": "BRONZE", "name": "Bronze member", "minSpend": 0, "earnMultiplier": 1.00},
                  {"code": "GOLD", "name": "Gold", "minSpend": 5000, "earnMultiplier": 2.00}
                ]}
                """);

        assertThat(replaced.status()).isEqualTo(200);
        Map<String, UUID> after = storedCodes(tenant);
        assertThat(after).containsOnlyKeys("BRONZE", "GOLD");
        assertThat(after.get("BRONZE")).as("a kept code keeps its identity").isEqualTo(bronzeBefore);
        assertThat(after.get("GOLD")).isNotEqualTo(bronzeBefore);
    }

    @Test
    void anUnchangedListLeavesTheProgramAlone() {
        TenantId tenant = configured();
        putTiers(tenant, "*", TWO_TIERS);

        ApiResponse again = putTiers(tenant, "*", TWO_TIERS);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().get("version").asLong()).as("nothing changed, so nothing moved").isEqualTo(2);
    }

    @Test
    void aListWithoutAZeroFirstTierIs422() {
        assertInvalid("""
                {"tiers": [{"code": "BRONZE", "name": "Bronze", "minSpend": 100, "earnMultiplier": 1.00}]}
                """);
    }

    @Test
    void aListWhoseThresholdsDoNotIncreaseIs422() {
        assertInvalid("""
                {"tiers": [
                  {"code": "BRONZE", "name": "Bronze", "minSpend": 0, "earnMultiplier": 1.00},
                  {"code": "SILVER", "name": "Silver", "minSpend": 1000, "earnMultiplier": 1.50},
                  {"code": "GOLD", "name": "Gold", "minSpend": 1000, "earnMultiplier": 2.00}
                ]}
                """);
    }

    @Test
    void aMultiplierBelowItsFloorIs422() {
        assertInvalid("""
                {"tiers": [{"code": "BRONZE", "name": "Bronze", "minSpend": 0, "earnMultiplier": 0.50}]}
                """);
    }

    @Test
    void anEmptyListIs422() {
        assertInvalid("""
                {"tiers": []}
                """);
    }

    @Test
    void aTierChangeMarksTheProgramForReevaluation() {
        TenantId tenant = configured();
        PostgresContainerConfig.executeAsOwner(
                "update loyalty_programs set tiers_dirty = false where tenant_id = '" + tenant + "'");
        assertThat(program(tenant).tiersDirty()).isFalse();

        putTiers(tenant, "*", TWO_TIERS);

        assertThat(program(tenant).tiersDirty())
                .as("a tier change makes every member's tier stale")
                .isTrue();
    }

    /** The member query port allows every removal today; this is the same use case with the answer a holder gives. */
    @Test
    void aRemovedTierThatAMemberHoldsIs409() {
        TenantId tenant = configured();
        putTiers(tenant, "*", TWO_TIERS);
        ReplaceTiersUseCase withHolders =
                new ReplaceTiersUseCase(unitOfWork, programs, tiers, code -> true, context, ids, clock);

        assertThatThrownBy(() -> inTenant(tenant, () -> withHolders.execute(
                        new ReplaceTiersUseCase.Command(List.of(
                                new TierRules.Input("BRONZE", "Bronze", 0L, new BigDecimal("1.00")))),
                        ExpectedVersion.fromIfMatch("*"))))
                .isInstanceOfSatisfying(ProgramRefusal.class, refusal -> {
                    assertThat(refusal.kind()).isEqualTo(ProgramRefusal.Kind.CONFLICT);
                    assertThat(refusal.code()).isEqualTo("TIER_IN_USE");
                });
        assertThat(storedCodes(tenant)).containsOnlyKeys("BRONZE", "SILVER");
    }

    // ------------------------------------------------------------------ helpers

    private TenantId configured() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        inTenant(tenant, () -> save.execute(
                new SaveProgramUseCase.Command("USD", 1000L, null, null, null, null), ExpectedVersion.fromIfMatch("*")));
        return tenant;
    }

    private Program program(TenantId tenant) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> programs.lock(tenant))).orElseThrow();
    }

    private Map<String, UUID> storedCodes(TenantId tenant) {
        List<Tier> stored = inTenant(tenant, () -> unitOfWork.transactional(tiers::findAll));
        return stored.stream().collect(Collectors.toMap(Tier::code, tier -> tier.id().value()));
    }

    private UUID idOf(TenantId tenant, String code) {
        return storedCodes(tenant).get(code);
    }

    private ApiResponse putTiers(TenantId tenant, String ifMatch, String body) {
        return call(
                tenant,
                put("/api/v1/tiers")
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                LoyaltyPermissions.PROGRAM_MANAGE);
    }

    private void assertInvalid(String body) {
        TenantId tenant = configured();
        ApiResponse response = putTiers(tenant, "*", body);
        assertThat(response.status()).as(body).isEqualTo(422);
        assertThat(response.code()).as(body).isEqualTo("TIER_LIST_INVALID");
        assertThat(storedCodes(tenant)).as("a refused list stores nothing").isEmpty();
    }
}
