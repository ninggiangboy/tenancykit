package dev.ngb.loyalty.support;

import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed earn vectors, read as data.
 *
 * <p>One file in {@code contracts/loyalty/earn-vectors} is one vector, and every vector is a case: it carries the program,
 * its tiers, its promotions, an optional starting member and the ordered steps with the status and the points each step
 * must answer. Reading them here rather than restating them in a test is what keeps the engine and the contract from
 * drifting apart.
 */
public final class EarnVectors {

    private static final Path DIRECTORY = RepositoryPaths.repositoryRoot().resolve("contracts/loyalty/earn-vectors");

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** One committed vector. */
    public record Vector(
            String file,
            String name,
            String note,
            Program program,
            List<Tier> tiers,
            List<Promotion> promotions,
            Member member,
            List<Step> steps) {

        public ZoneId zone() {
            return ZoneId.of(program.timeZone());
        }

        /** The promotion set and program of this vector, which a step's {@code rules} replaces for the later steps. */
        public Rules rules() {
            return new Rules(program, tiers, promotions);
        }
    }

    public record Program(
            String currency,
            Long currencyUnit,
            String timeZone,
            Integer expiryMonths,
            Integer expiryNoticeDays,
            Integer tierWindowMonths) {

        public int expiryMonthsOrDefault() {
            return expiryMonths == null ? 12 : expiryMonths;
        }
    }

    public record Tier(String code, Long minSpend, BigDecimal earnMultiplier) {}

    public record Promotion(
            String id,
            BigDecimal multiplier,
            Integer priority,
            String sku,
            String category,
            String storeCode,
            String channel,
            String tier,
            List<String> daysOfWeek,
            String from,
            String to) {

        public int priorityOrDefault() {
            return priority == null ? 0 : priority;
        }
    }

    public record Member(String externalRef, String tier, Long debt) {

        public long debtOrDefault() {
            return debt == null ? 0L : debt;
        }
    }

    public record Rules(Program program, List<Tier> tiers, List<Promotion> promotions) {}

    /** One call: an order or a return, the body to send, and what it must answer. */
    public record Step(
            String call,
            String orderRef,
            String returnRef,
            Rules rules,
            JsonNode body,
            Integer expectedStatus,
            Long expectedPoints,
            Long expectedLedgerDelta,
            String expectedError) {

        public boolean isOrder() {
            return "order".equals(call);
        }
    }

    private EarnVectors() {}

    /** Every vector, in file order, so a failure names the file it came from. */
    public static List<Vector> all() {
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .filter(file -> !file.getFileName().toString().equals("vector.schema.json"))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .map(EarnVectors::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Vector read(Path file) {
        try {
            Vector parsed = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Vector.class);
            return new Vector(file.getFileName().toString(), parsed.name(), parsed.note(), parsed.program(),
                    parsed.tiers(), parsed.promotions(), parsed.member(), parsed.steps());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not read the vector " + file, e);
        }
    }

    /** A promotion as the engine reads it: its conditions are the vector's single values and its window is local. */
    public static PromotionView view(Promotion promotion, ZoneId zone) {
        return new PromotionView(
                UUID.nameUUIDFromBytes(promotion.id().getBytes(StandardCharsets.UTF_8)),
                promotion.id(),
                promotion.priorityOrDefault(),
                PromotionStatus.ACTIVE,
                promotion.multiplier(),
                instant(promotion.from(), zone),
                instant(promotion.to(), zone),
                list(promotion.category()),
                list(promotion.sku()),
                list(promotion.storeCode()),
                weekdays(promotion.daysOfWeek()),
                list(promotion.tier()),
                null,
                null,
                zone,
                1L);
    }

    /** A local wall-clock date-time of the vector, which the window is expressed in, as the instant it names. */
    public static Instant instant(String localDateTime, ZoneId zone) {
        return localDateTime == null ? null : LocalDateTime.parse(localDateTime).atZone(zone).toInstant();
    }

    /** ISO weekdays: the vector writes MON to SUN, the stored rule holds 1 to 7. */
    public static List<Integer> weekdays(List<String> days) {
        if (days == null) {
            return List.of();
        }
        return days.stream().map(EarnVectors::weekday).toList();
    }

    private static Integer weekday(String day) {
        return switch (day) {
            case "MON" -> 1;
            case "TUE" -> 2;
            case "WED" -> 3;
            case "THU" -> 4;
            case "FRI" -> 5;
            case "SAT" -> 6;
            case "SUN" -> 7;
            default -> throw new IllegalArgumentException("not a weekday: " + day);
        };
    }

    /** The multiplier of the tier a member holds, or of the lowest tier when it holds none. */
    public static BigDecimal tierMultiplier(String tierCode, List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return BigDecimal.ONE;
        }
        for (Tier tier : tiers) {
            if (tier.code().equals(tierCode)) {
                return tier.earnMultiplier();
            }
        }
        return tiers.getFirst().earnMultiplier();
    }

    /** The tier code a member effectively holds: its own, or the lowest one when the vector seeds none. */
    public static String tierCode(String tierCode, List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        for (Tier tier : tiers) {
            if (tier.code().equals(tierCode)) {
                return tier.code();
            }
        }
        return tiers.getFirst().code();
    }

    private static List<String> list(String value) {
        return value == null ? List.of() : List.of(value);
    }
}
