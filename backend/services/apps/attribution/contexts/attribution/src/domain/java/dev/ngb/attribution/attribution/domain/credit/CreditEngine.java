package dev.ngb.attribution.attribution.domain.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The credit engine: a pure function from a touch chain, a model, a window, a value and a currency to the credit each
 * click receives.
 *
 * <p>It is the one place a conversion's value is divided, and it is written against
 * {@code contracts/attribution/attribution-vectors/} rather than the other way round: a case states the credits a
 * reviewer would defend, and adding model behaviour means adding a case there. Two rules make a case decidable — only
 * human clicks count (a {@code BOT} or {@code SUSPECT} click is filtered out first), and the credits sum exactly to the
 * value (linear credit floors each share and gives the residual to the last touch, at the currency's fraction digits).
 *
 * <p>All arithmetic is {@link BigDecimal} at the currency's scale: a binary float would make {@code 33.33 + 33.33 +
 * 33.34} not equal to {@code 100.00} and a report disagree with revenue.
 */
public final class CreditEngine {

    /** The model, as the vectors name it. */
    public enum Model {
        FIRST,
        LAST,
        LINEAR
    }

    /**
     * One click in the chain.
     *
     * @param clickId the click's identity, as the vectors name it
     * @param linkId the link it came through
     * @param campaignId the campaign the link belongs to, absent for a link outside a campaign
     * @param at when the click happened
     * @param human whether the click classified as a person; a bot or suspect click is never credited
     */
    public record Touch(String clickId, String linkId, String campaignId, Instant at, boolean human) {}

    /**
     * One click's share of a conversion.
     *
     * @param clickId the credited click, naming an entry of the chain
     * @param linkId the link it came through
     * @param campaignId the campaign the link belongs to
     * @param weight the fraction of the value this click received, in {@code [0, 1]}
     * @param credit the amount, at the currency's fraction digits
     */
    public record Credit(String clickId, String linkId, String campaignId, BigDecimal weight, BigDecimal credit) {}

    /**
     * The outcome of one conversion.
     *
     * @param credits the credit per click, in chain order; empty means the conversion is unattributed
     * @param owningCampaignId the campaign of the last click in the chain, which owns the conversion, or null
     * @param reason why the conversion is unattributed, or null when it is credited
     */
    public record Result(List<Credit> credits, String owningCampaignId, String reason) {

        public Result {
            credits = List.copyOf(credits);
        }

        public boolean attributed() {
            return !credits.isEmpty();
        }
    }

    private CreditEngine() {}

    /**
     * Divide a conversion's value among the clicks that led to it.
     *
     * @param chain the touchpoints, oldest first; the order is not assumed, and the filter and model sort by time
     * @param model the model of the owning campaign
     * @param windowDays the owning campaign's window, 1 to 90 days
     * @param value the conversion value, as a decimal
     * @param currencyCode the ISO 4217 code that decides the fraction digits
     * @param conversionAt when the conversion happened
     */
    public static Result credit(
            List<Touch> chain,
            Model model,
            int windowDays,
            BigDecimal value,
            String currencyCode,
            Instant conversionAt) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(conversionAt, "conversionAt");
        if (windowDays < 1) {
            throw new IllegalArgumentException("an attribution window is at least one day");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("a conversion value is not negative");
        }
        int scale = fractionDigits(currencyCode);
        List<Touch> inWindow = inWindow(chain, windowDays, conversionAt);
        if (inWindow.isEmpty()) {
            // The reason distinguishes "no chain at all" from "a chain whose clicks were all too old or all machines",
            // because the two tell a Tenant different things.
            boolean anyHuman = chain != null && chain.stream().anyMatch(Touch::human);
            return new Result(List.of(), null, anyHuman ? "all_clicks_outside_window" : (chain == null || chain.isEmpty()
                    ? "no_chain"
                    : "bot_only"));
        }
        String owningCampaign = inWindow.getLast().campaignId();
        List<Credit> credits = switch (model) {
            case FIRST -> single(inWindow.getFirst(), value, scale);
            case LAST -> single(inWindow.getLast(), value, scale);
            case LINEAR -> linear(inWindow, value, scale);
        };
        return new Result(credits, owningCampaign, null);
    }

    /**
     * The clicks that count, oldest first: human, not after the conversion, and no more than the window before it.
     *
     * <p>The window is counted as 24-hour periods from the conversion instant, so a click exactly {@code windowDays}
     * before it is inside and one second past is outside. A click after the conversion is never credited, which keeps a
     * late-arriving click from stealing an earlier conversion's credit.
     */
    static List<Touch> inWindow(List<Touch> chain, int windowDays, Instant conversionAt) {
        if (chain == null || chain.isEmpty()) {
            return List.of();
        }
        Instant earliest = conversionAt.minus(Duration.ofDays(windowDays));
        List<Touch> candidates = new ArrayList<>();
        for (Touch touch : chain) {
            if (!touch.human()) {
                continue;
            }
            if (touch.at().isAfter(conversionAt)) {
                continue;
            }
            if (touch.at().isBefore(earliest)) {
                continue;
            }
            candidates.add(touch);
        }
        // Oldest first, so FIRST is the earliest and the last is the most recent; the vectors are already ordered but a
        // stored chain from a database may not be.
        candidates.sort(java.util.Comparator.comparing(Touch::at).thenComparing(Touch::clickId));
        return candidates;
    }

    private static List<Credit> single(Touch touch, BigDecimal value, int scale) {
        BigDecimal credit = value.setScale(scale, RoundingMode.HALF_UP);
        return List.of(new Credit(touch.clickId(), touch.linkId(), touch.campaignId(), BigDecimal.ONE, credit));
    }

    /**
     * Split the value evenly, floor each share at the currency's scale, and give the residual to the last touch.
     *
     * <p>This is the rule the vectors pin: {@code 10.00} over three touches is {@code 3.33, 3.33, 3.34}, and {@code 100}
     * in a zero-digit currency over three is {@code 33, 33, 34}. The sum is exactly the value, so a report can add the
     * credits and a reconciliation can trust them.
     */
    private static List<Credit> linear(List<Touch> touches, BigDecimal value, int scale) {
        int count = touches.size();
        BigDecimal share = value.divide(BigDecimal.valueOf(count), scale, RoundingMode.DOWN);
        BigDecimal weight = BigDecimal.ONE.divide(BigDecimal.valueOf(count), 8, RoundingMode.DOWN);
        List<Credit> credits = new ArrayList<>(count);
        BigDecimal assigned = BigDecimal.ZERO;
        for (int index = 0; index < count; index++) {
            BigDecimal credit = share;
            if (index == count - 1) {
                // The residual goes to the last touch, so the credits sum exactly to the value.
                credit = value.subtract(assigned);
            }
            assigned = assigned.add(credit);
            Touch touch = touches.get(index);
            credits.add(new Credit(touch.clickId(), touch.linkId(), touch.campaignId(), weight, credit));
        }
        return credits;
    }

    /** The fraction digits the currency uses: 2 for USD, 0 for VND and JPY. */
    public static int fractionDigits(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return 2;
        }
        try {
            return Currency.getInstance(currencyCode.toUpperCase(java.util.Locale.ROOT)).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            // A code the JDK does not know is treated as a two-digit currency rather than failing a conversion.
            return 2;
        }
    }
}
