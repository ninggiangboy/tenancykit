package dev.ngb.billing.coupon.domain.coupon;

import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Objects;

/**
 * What a coupon gives. The three shapes are the whole benefit vocabulary; a new kind of promotion is a new record
 * here, not a flag on {@link Coupon}, so the discount engine, the payload and the API stay exhaustive.
 *
 * <p>A percent coupon is stored in basis points because a percentage held as a {@code double} rounds differently on
 * every machine. A fixed amount and a wallet credit are money in the coupon's own currency, which is also the
 * wallet's: a coupon never crosses a currency boundary.
 */
public sealed interface Benefit permits Benefit.PercentOff, Benefit.AmountOff, Benefit.WalletCredit {

    /** The wire name of the shape, used by the event payload and the API. */
    String type();

    /** A percentage off the charge, from 1 basis point (0.01%) to 10,000 (100%). */
    record PercentOff(int basisPoints) implements Benefit {

        public PercentOff {
            if (basisPoints < 1 || basisPoints > 10_000) {
                throw new IllegalArgumentException("a percent benefit is between 1 and 10000 basis points");
            }
        }

        @Override
        public String type() {
            return "PERCENT_OFF";
        }
    }

    /** A fixed amount off the charge, in the coupon's currency. */
    record AmountOff(Money amount) implements Benefit {

        public AmountOff {
            Objects.requireNonNull(amount, "amount");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("an amount-off benefit must be positive");
            }
        }

        @Override
        public String type() {
            return "AMOUNT_OFF";
        }
    }

    /** Money added to the wallet once, when the coupon is redeemed. It is never applied to a charge. */
    record WalletCredit(Money amount) implements Benefit {

        public WalletCredit {
            Objects.requireNonNull(amount, "amount");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("a wallet-credit benefit must be positive");
            }
        }

        @Override
        public String type() {
            return "WALLET_CREDIT";
        }
    }
}
