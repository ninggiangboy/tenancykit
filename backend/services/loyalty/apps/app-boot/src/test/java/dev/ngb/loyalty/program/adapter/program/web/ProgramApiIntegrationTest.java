package dev.ngb.loyalty.program.adapter.program.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.application.program.command.SaveProgramUseCase;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import dev.ngb.loyalty.program.application.program.port.ProgramRepository;
import dev.ngb.loyalty.program.domain.program.Program;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.LoyaltyApiTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The program endpoint: it does not exist until a Tenant writes it, the write is conditional, and a value out of range is
 * refused with the code the API document names.
 */
class ProgramApiIntegrationTest extends LoyaltyApiTest {

    private static final String MINIMAL = """
            {"currency": "USD", "currencyUnit": 1000}
            """;

    @Autowired SaveProgramUseCase save;
    @Autowired ProgramRepository programs;
    @Autowired UnitOfWork unitOfWork;
    @Autowired TenantContext context;
    @Autowired Clock clock;

    @Test
    void aProgramMustBeConfiguredBeforeItCanBeRead() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        ApiResponse program = call(tenant, get("/api/v1/program"), LoyaltyPermissions.PROGRAM_READ);
        ApiResponse tiers = call(tenant, get("/api/v1/tiers"), LoyaltyPermissions.PROGRAM_READ);

        assertThat(program.status()).isEqualTo(404);
        assertThat(program.code()).isEqualTo("PROGRAM_NOT_CONFIGURED");
        assertThat(tiers.status()).isEqualTo(404);
        assertThat(tiers.code()).isEqualTo("PROGRAM_NOT_CONFIGURED");
    }

    @Test
    void theFirstWriteCreatesTheProgramAndAppliesTheDocumentedDefaults() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        ApiResponse created = putProgram(tenant, "*", MINIMAL);

        assertThat(created.status()).isEqualTo(200);
        assertThat(created.etag()).isEqualTo("\"1\"");
        assertThat(created.json().get("currency").asString()).isEqualTo("USD");
        assertThat(created.json().get("currencyUnit").asLong()).isEqualTo(1000);
        assertThat(created.json().get("expiryMonths").asInt()).isEqualTo(12);
        assertThat(created.json().get("expiryNoticeDays").asInt()).isEqualTo(30);
        assertThat(created.json().get("tierWindowMonths").asInt()).isEqualTo(12);
        assertThat(created.json().get("timeZone").asString()).isEqualTo("UTC");
        assertThat(created.json().get("version").asLong()).isEqualTo(1);

        ApiResponse read = call(tenant, get("/api/v1/program"), LoyaltyPermissions.PROGRAM_READ);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.etag()).isEqualTo("\"1\"");
        assertThat(read.json().get("currency").asString()).isEqualTo("USD");
    }

    @Test
    void aReplaceKeepsWhatTheCallerSetAndMovesTheVersion() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        putProgram(tenant, "*", MINIMAL);

        ApiResponse replaced = putProgram(
                tenant,
                "\"1\"",
                """
                {"currency": "USD", "currencyUnit": 500, "expiryMonths": 6, "expiryNoticeDays": 0,
                 "tierWindowMonths": 24, "timeZone": "Europe/Paris"}
                """);

        assertThat(replaced.status()).isEqualTo(200);
        assertThat(replaced.etag()).isEqualTo("\"2\"");
        assertThat(replaced.json().get("currencyUnit").asLong()).isEqualTo(500);
        assertThat(replaced.json().get("expiryMonths").asInt()).isEqualTo(6);
        assertThat(replaced.json().get("expiryNoticeDays").asInt()).isZero();
        assertThat(replaced.json().get("tierWindowMonths").asInt()).isEqualTo(24);
        assertThat(replaced.json().get("timeZone").asString()).isEqualTo("Europe/Paris");
    }

    @Test
    void aWriteWithoutIfMatchAnswers428() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        ApiResponse response = call(
                tenant,
                put("/api/v1/program").contentType(MediaType.APPLICATION_JSON).content(MINIMAL),
                LoyaltyPermissions.PROGRAM_MANAGE);

        assertThat(response.status()).isEqualTo(428);
        assertThat(response.code()).isEqualTo("IF_MATCH_REQUIRED");
    }

    @Test
    void aWriteAgainstAnOlderVersionAnswers412WithTheCurrentETag() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        putProgram(tenant, "*", MINIMAL);

        ApiResponse response = putProgram(tenant, "\"7\"", MINIMAL);

        assertThat(response.status()).isEqualTo(412);
        assertThat(response.etag()).isEqualTo("\"1\"");
    }

    @Test
    void aValueOutOfRangeIs422WithValidationFailed() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        assertInvalid(tenant, """
                {"currency": "usd", "currencyUnit": 1000}
                """);
        assertInvalid(tenant, """
                {"currency": "USD", "currencyUnit": 0}
                """);
        assertInvalid(tenant, """
                {"currency": "USD", "currencyUnit": 1000, "expiryMonths": 121}
                """);
        assertInvalid(tenant, """
                {"currency": "USD", "currencyUnit": 1000, "expiryNoticeDays": 366}
                """);
        assertInvalid(tenant, """
                {"currency": "USD", "currencyUnit": 1000, "tierWindowMonths": 0}
                """);
        assertInvalid(tenant, """
                {"currency": "USD", "currencyUnit": 1000, "timeZone": "Mars/Olympus"}
                """);
    }

    @Test
    void aWriteNeedsTheManagePermission() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        ApiResponse response = call(
                tenant,
                put("/api/v1/program")
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MINIMAL),
                LoyaltyPermissions.PROGRAM_READ);

        assertThat(response.status()).isEqualTo(403);
    }

    /**
     * The ledger query port allows a currency change today because nothing has earned; this is the same use case with the
     * answer the points ledger will give once an entry exists.
     */
    @Test
    void theCurrencyIsFrozenOnceALedgerEntryExists() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        inTenant(tenant, () -> save.execute(
                new SaveProgramUseCase.Command("USD", 1000L, null, null, null, null), ExpectedVersion.fromIfMatch("*")));

        SaveProgramUseCase frozen = new SaveProgramUseCase(unitOfWork, programs, () -> true, context, clock);

        assertThatThrownBy(() -> inTenant(tenant, () -> frozen.execute(
                        new SaveProgramUseCase.Command("EUR", 1000L, null, null, null, null),
                        ExpectedVersion.fromIfMatch("\"1\""))))
                .isInstanceOfSatisfying(ProgramRefusal.class, refusal -> {
                    assertThat(refusal.kind()).isEqualTo(ProgramRefusal.Kind.CONFLICT);
                    assertThat(refusal.code()).isEqualTo("CURRENCY_IMMUTABLE");
                });

        Program stored = inTenant(tenant, () -> stored(tenant));
        assertThat(stored.currency()).isEqualTo("USD");
    }

    @Test
    void theCurrencyStaysChangeableWhileTheLedgerIsEmpty() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        inTenant(tenant, () -> save.execute(
                new SaveProgramUseCase.Command("USD", 1000L, null, null, null, null), ExpectedVersion.fromIfMatch("*")));

        inTenant(tenant, () -> save.execute(
                new SaveProgramUseCase.Command("EUR", 1000L, null, null, null, null), ExpectedVersion.fromIfMatch("\"1\"")));

        assertThat(inTenant(tenant, () -> stored(tenant)).currency()).isEqualTo("EUR");
    }

    // ------------------------------------------------------------------ helpers

    private Program stored(TenantId tenant) {
        return unitOfWork.transactional(() -> programs.lock(tenant)).orElseThrow();
    }

    private ApiResponse putProgram(TenantId tenant, String ifMatch, String body) {
        return call(
                tenant,
                put("/api/v1/program")
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                LoyaltyPermissions.PROGRAM_MANAGE);
    }

    private void assertInvalid(TenantId tenant, String body) {
        ApiResponse response = putProgram(tenant, "*", body);
        assertThat(response.status()).as(body).isEqualTo(422);
        assertThat(response.code()).as(body).isEqualTo("VALIDATION_FAILED");
    }
}
