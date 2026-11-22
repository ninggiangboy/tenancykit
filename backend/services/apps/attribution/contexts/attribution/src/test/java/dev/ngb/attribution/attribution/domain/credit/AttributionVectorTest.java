package dev.ngb.attribution.attribution.domain.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vector runner: every file in {@code contracts/attribution/attribution-vectors/} is loaded and the engine must
 * reproduce its credits exactly.
 *
 * <p>This is the only way a model test exists, which is the contract's point: a behaviour is added to a vector file, not
 * to a test here. A new case that the engine does not satisfy fails this runner and names the case.
 */
class AttributionVectorTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void everyVectorCaseIsReproducedExactly() throws Exception {
        List<Path> files = vectorFiles();
        assertThat(files).as("the vector files must exist and be committed").isNotEmpty();

        List<String> failures = new ArrayList<>();
        int cases = 0;
        for (Path file : files) {
            JsonNode root;
            try (InputStream in = Files.newInputStream(file)) {
                root = JSON.readTree(in);
            }
            for (JsonNode vector : root) {
                cases++;
                String violation = check(vector);
                if (violation != null) {
                    failures.add(file.getFileName() + " / " + vector.get("name").asString() + ": " + violation);
                }
            }
        }

        assertThat(cases).as("the runner must actually load cases").isGreaterThan(0);
        assertThat(failures).as("every vector case must be reproduced exactly").isEmpty();
    }

    private String check(JsonNode vector) {
        String model = vector.get("model").asString();
        int windowDays = vector.get("window_days").asInt();
        String currency = vector.get("currency").asString();
        Instant conversionAt = Instant.parse(vector.get("conversion").get("at").asString());
        BigDecimal value = new BigDecimal(vector.get("conversion").get("value").asString());

        List<CreditEngine.Touch> chain = new ArrayList<>();
        for (JsonNode touch : vector.get("chain")) {
            chain.add(new CreditEngine.Touch(
                    touch.get("click_id").asString(),
                    touch.get("link_id").asString(),
                    touch.get("campaign_id").asString(),
                    Instant.parse(touch.get("at").asString()),
                    "HUMAN".equals(touch.get("traffic_class").asString())));
        }

        CreditEngine.Result result = CreditEngine.credit(
                chain, CreditEngine.Model.valueOf(model), windowDays, value, currency, conversionAt);

        List<JsonNode> expected = new ArrayList<>();
        for (JsonNode credit : vector.get("expect")) {
            expected.add(credit);
        }
        if (expected.isEmpty()) {
            if (result.attributed()) {
                return "expected no credit but the engine credited " + result.credits();
            }
            JsonNode reason = vector.get("reason");
            if (reason != null && !reason.asString().equals(result.reason())) {
                return "expected reason " + reason.asString() + " but the engine said " + result.reason();
            }
            return null;
        }
        if (result.credits().size() != expected.size()) {
            return "expected " + expected.size() + " credits but the engine produced " + result.credits().size();
        }
        for (int index = 0; index < expected.size(); index++) {
            JsonNode want = expected.get(index);
            CreditEngine.Credit got = result.credits().get(index);
            if (!want.get("click_id").asString().equals(got.clickId())) {
                return "credit " + index + " names " + got.clickId() + " but the vector names "
                        + want.get("click_id").asString();
            }
            BigDecimal wantCredit = new BigDecimal(want.get("credit").asString());
            if (wantCredit.compareTo(got.credit()) != 0) {
                return "credit " + index + " is " + got.credit() + " but the vector expects " + wantCredit;
            }
        }
        // The sum must be exactly the value, which is the invariant the residual rule exists for.
        BigDecimal sum = result.credits().stream()
                .map(CreditEngine.Credit::credit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(value) != 0) {
            return "the credits sum to " + sum + " but the value is " + value;
        }
        return null;
    }

    private static List<Path> vectorFiles() throws Exception {
        Path dir = repositoryRoot().resolve("contracts/attribution/attribution-vectors");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("vector.schema.json"))
                    .sorted()
                    .toList();
        }
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("contracts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
