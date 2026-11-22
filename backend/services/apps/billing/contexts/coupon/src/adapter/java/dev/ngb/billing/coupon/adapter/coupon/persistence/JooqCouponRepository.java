package dev.ngb.billing.coupon.adapter.coupon.persistence;

import static dev.ngb.billing_jooq.Tables.COUPONS;

import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.ChargeKind;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.CouponStatus;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.Limits;
import dev.ngb.billing.coupon.domain.coupon.Scope;
import dev.ngb.billing.coupon.domain.coupon.TimeWindow;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing_jooq.tables.records.CouponsRecord;
import dev.ngb.core.id.TenantId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class JooqCouponRepository implements CouponRepository {

    private final DSLContext dsl;

    public JooqCouponRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(Coupon coupon) {
        CouponsRecord record = dsl.newRecord(COUPONS);
        record.setId(coupon.id().value());
        record.setTenantId(coupon.tenantId().value());
        record.setName(coupon.name());
        record.setCurrency(coupon.currency().code());
        record.setBenefitType(coupon.benefit().type());
        switch (coupon.benefit()) {
            case Benefit.PercentOff percent -> record.setPercentBasisPoints(percent.basisPoints());
            case Benefit.AmountOff amountOff -> record.setAmountOff(amountOff.amount().minorUnits());
            case Benefit.WalletCredit credit -> record.setCreditAmount(credit.amount().minorUnits());
        }
        if (coupon.duration().window() != null) {
            record.setWindowCount(coupon.duration().window().count());
            record.setWindowUnit(coupon.duration().window().unit().name());
        }
        record.setMaxApplications(coupon.duration().maxApplications());
        record.setMaxTotalDiscount(minorUnits(coupon.duration().maxTotalDiscount()));
        record.setScopeKinds(kindNames(coupon.scope()));
        record.setScopePlanIds(coupon.scope().planIds().toArray(UUID[]::new));
        record.setScopeMetrics(coupon.scope().metrics().toArray(String[]::new));
        record.setRedeemBy(coupon.limits().redeemBy());
        record.setMaxRedemptions(coupon.limits().maxRedemptions());
        record.setMaxPerCustomer(coupon.limits().maxPerCustomer());
        record.setMinCharge(minorUnits(coupon.limits().minCharge()));
        record.setMaxDiscountPerApplication(minorUnits(coupon.limits().maxDiscountPerApplication()));
        record.setBudget(minorUnits(coupon.limits().budget()));
        record.setStackable(coupon.stackable());
        record.setStatus(coupon.status().name());
        record.setCreatedBy(coupon.createdBy());
        record.setCreatedAt(coupon.createdAt());
        record.setDeactivatedAt(coupon.deactivatedAt());
        record.insert();
    }

    @Override
    public void update(Coupon coupon) {
        dsl.update(COUPONS)
                .set(COUPONS.STATUS, coupon.status().name())
                .set(COUPONS.DEACTIVATED_AT, coupon.deactivatedAt())
                .where(COUPONS.ID.eq(coupon.id().value()))
                .execute();
    }

    @Override
    public Optional<Coupon> findById(CouponId id) {
        return dsl.selectFrom(COUPONS).where(COUPONS.ID.eq(id.value())).fetchOptional(JooqCouponRepository::toDomain);
    }

    @Override
    public Optional<Coupon> lockById(CouponId id) {
        return dsl.selectFrom(COUPONS).where(COUPONS.ID.eq(id.value())).forUpdate().fetchOptional(JooqCouponRepository::toDomain);
    }

    @Override
    public boolean countRedemption(CouponId id) {
        return dsl.update(COUPONS)
                .set(COUPONS.REDEMPTIONS_COUNT, COUPONS.REDEMPTIONS_COUNT.plus(1))
                .where(COUPONS.ID.eq(id.value()))
                .and(DSL.or(COUPONS.MAX_REDEMPTIONS.isNull(), COUPONS.REDEMPTIONS_COUNT.lt(COUPONS.MAX_REDEMPTIONS)))
                .execute() == 1;
    }

    @Override
    public long spendBudget(CouponId id, long amount) {
        if (amount <= 0) {
            return 0;
        }
        int updated = dsl.update(COUPONS)
                .set(COUPONS.BUDGET_USED, COUPONS.BUDGET_USED.plus(amount))
                .where(COUPONS.ID.eq(id.value()))
                .and(COUPONS.BUDGET.isNotNull())
                .and(COUPONS.BUDGET_USED.plus(amount).le(COUPONS.BUDGET))
                .execute();
        return updated == 1 ? amount : 0;
    }

    @Override
    public long budgetUsed(CouponId id) {
        Long used = dsl.select(COUPONS.BUDGET_USED).from(COUPONS).where(COUPONS.ID.eq(id.value())).fetchOne(COUPONS.BUDGET_USED);
        return used == null ? 0 : used;
    }

    private static Coupon toDomain(CouponsRecord row) {
        Currency currency = Currency.of(row.getCurrency().strip());
        Benefit benefit = switch (row.getBenefitType()) {
            case "PERCENT_OFF" -> new Benefit.PercentOff(row.getPercentBasisPoints());
            case "AMOUNT_OFF" -> new Benefit.AmountOff(Money.of(row.getAmountOff(), currency));
            case "WALLET_CREDIT" -> new Benefit.WalletCredit(Money.of(row.getCreditAmount(), currency));
            default -> throw new IllegalStateException("unknown benefit type " + row.getBenefitType());
        };
        TimeWindow window = row.getWindowCount() == null
                ? null
                : new TimeWindow(row.getWindowCount(), TimeWindow.Unit.valueOf(row.getWindowUnit()));
        Duration duration = new Duration(window, row.getMaxApplications(), money(row.getMaxTotalDiscount(), currency));
        Scope scope = new Scope(kinds(row.getScopeKinds()), new LinkedHashSet<>(Arrays.asList(row.getScopePlanIds())),
                new LinkedHashSet<>(Arrays.asList(row.getScopeMetrics())));
        Limits limits = new Limits(row.getRedeemBy(), row.getMaxRedemptions(), row.getMaxPerCustomer(),
                money(row.getMinCharge(), currency), money(row.getMaxDiscountPerApplication(), currency), money(row.getBudget(), currency));
        return Coupon.restore(new CouponId(row.getId()), new TenantId(row.getTenantId()), row.getName(), benefit, duration, scope, limits,
                currency, row.getStackable(), row.getCreatedBy(), row.getCreatedAt(), CouponStatus.valueOf(row.getStatus()),
                row.getDeactivatedAt());
    }

    private static String[] kindNames(Scope scope) {
        return scope.kinds().stream().map(Enum::name).toArray(String[]::new);
    }

    private static Set<ChargeKind> kinds(String[] names) {
        Set<ChargeKind> kinds = new LinkedHashSet<>();
        if (names != null) {
            Arrays.stream(names).map(ChargeKind::valueOf).forEach(kinds::add);
        }
        return kinds;
    }

    private static Long minorUnits(Money money) {
        return money == null ? null : money.minorUnits();
    }

    private static Money money(Long minorUnits, Currency currency) {
        return minorUnits == null ? null : Money.of(minorUnits, currency);
    }
}
