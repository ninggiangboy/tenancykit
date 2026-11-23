package dev.ngb.core.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CallerLabelRulesTest {

    private static String document(String path, String operation) {
        return """
                openapi: 3.1.0
                paths:
                  "%s":
                    get:
                      operationId: read
                      %s
                """.formatted(path, operation);
    }

    private static String labels(String... callers) {
        StringBuilder text = new StringBuilder("x-allowed-callers:\n");
        for (String caller : callers) {
            text.append("      - ").append(caller).append('\n');
        }
        return text.toString();
    }

    @Test
    void aLabelledOperationPasses() {
        CallerLabelRules.check(document("/api/v1/wallets", labels("tenant-user", "tenant-system")), false);
        CallerLabelRules.check(document("/api/v1/wallets", labels("tenant-user")), true);
    }

    @Test
    void aMissingLabelFails() {
        assertThatThrownBy(() -> CallerLabelRules.check(document("/api/v1/wallets", "summary: no caller"), false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no caller on GET /api/v1/wallets");
    }

    @Test
    void anUnknownCallerFails() {
        assertThatThrownBy(() -> CallerLabelRules.check(document("/api/v1/wallets", labels("audience")), false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("unknown caller 'audience'");
    }

    @Test
    void theRetiredPublicFlagFails() {
        assertThatThrownBy(() -> CallerLabelRules.check(document("/api/v1/wallets", labels("public") + "      x-public: true\n"), false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("retired x-public");
    }

    @Test
    void twoCallersOnOnePathFailOnlyWhenTheSwitchIsOn() {
        String two = document("/api/v1/wallets", labels("tenant-user", "tenant-system"));

        CallerLabelRules.check(two, false);
        assertThatThrownBy(() -> CallerLabelRules.check(two, true))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("more than one caller");
    }

    @Test
    void theTwoSharedPathsMayKeepTwoCallers() {
        String me = document("/api/v1/me", labels("platform-user", "tenant-user"));
        String token = document("/oauth/token", labels("tenant-system", "internal"));

        CallerLabelRules.check(me, true);
        CallerLabelRules.check(token, true);
    }

    @Test
    void aDocumentWithoutPathsFails() {
        assertThatThrownBy(() -> CallerLabelRules.check("openapi: 3.1.0\n", false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no paths");
    }

    @Test
    void aCallerUnderAPrefixItDoesNotServeFailsWhenTheRuleIsOn() {
        String wrong = document("/system/v1/wallets", labels("tenant-user"));

        CallerLabelRules.check(wrong, false, false);
        assertThatThrownBy(() -> CallerLabelRules.check(wrong, false, true))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("is under /system/v1 but names tenant-user");
    }

    @Test
    void aCallerUnderItsOwnPrefixPasses() {
        CallerLabelRules.check(document("/system/v1/wallets", labels("tenant-system")), false, true);
        CallerLabelRules.check(document("/api/v1/wallets", labels("tenant-user")), false, true);
        CallerLabelRules.check(document("/admin/v1/tenants", labels("platform-user")), false, true);
        CallerLabelRules.check(document("/internal/v1/replica/snapshot", labels("internal")), false, true);
    }

    @Test
    void theMePathAcceptsBothPersonKinds() {
        CallerLabelRules.check(document("/api/v1/me", labels("platform-user", "tenant-user")), false, true);
    }

    @Test
    void theRulesReadARealisticDocument() {
        String yaml = """
                openapi: 3.1.0
                paths:
                  /api/v1/wallets:
                    get:
                      operationId: list
                      x-allowed-callers:
                      - tenant-user
                      - tenant-system
                    post:
                      operationId: create
                      x-allowed-callers:
                      - tenant-user
                  /p/l/{token}:
                    post:
                      operationId: open
                      x-allowed-callers:
                      - public
                """;

        CallerLabelRules.check(yaml, false);
        assertThat(yaml).contains("x-allowed-callers");
    }
}
