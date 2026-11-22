package dev.ngb.customer.activity.application.activity.query;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.activity.application.activity.service.ActivityRules;
import dev.ngb.customer.customers.api.customer.CustomerDirectory;
import dev.ngb.customer.customers.api.customer.CustomerRef;
import java.time.Instant;

/** Reads the current tenant's activities for the GET handler. */
public class ActivityQueries {

    /** The page size a caller gets when it asks for none, and the largest page a caller may ask for. */
    public static final int DEFAULT_LIMIT = 50;

    public static final int MAX_LIMIT = 200;

    private final UnitOfWork unitOfWork;
    private final ActivityReadRepository activities;
    private final CustomerDirectory customers;

    public ActivityQueries(
            UnitOfWork unitOfWork, ActivityReadRepository activities, CustomerDirectory customers) {
        this.unitOfWork = unitOfWork;
        this.activities = activities;
        this.customers = customers;
    }

    /**
     * A page of one customer's timeline, newest first.
     *
     * @throws NotFoundException when the tenant has no customer with that reference
     */
    public Page<ActivityView> forCustomer(
            String externalRef, String type, Instant from, Instant to, Integer limit, String cursor) {
        String ref = ActivityRules.reference(externalRef, ActivityRules.MAX_EXTERNAL_REF, "customer");
        ActivityFilter filter = new ActivityFilter(type(type), from, to);
        int page = pageSize(limit);
        return unitOfWork.transactional(() -> {
            CustomerRef customer = customers
                    .findByExternalRef(ref)
                    .orElseThrow(() -> new NotFoundException("No customer exists here with that reference."));
            return activities.findByCustomer(customer.customerId(), filter, page, cursor);
        });
    }

    private static String type(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String type = raw.strip();
        if (type.length() > 64) {
            throw new BadRequestException("A type filter is at most 64 characters.");
        }
        return type;
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("A page size is 1 to " + MAX_LIMIT + ".");
        }
        return limit;
    }
}
