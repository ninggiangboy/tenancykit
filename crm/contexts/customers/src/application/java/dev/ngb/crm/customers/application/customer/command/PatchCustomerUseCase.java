package dev.ngb.crm.customers.application.customer.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.crm.customers.application.customer.exception.CustomerErasedException;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.application.customer.service.CustomerFields;
import dev.ngb.crm.customers.application.customer.service.CustomerRules;
import dev.ngb.crm.customers.application.tag.service.TagRules;
import dev.ngb.crm.customers.domain.customer.Customer;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code PATCH /customers/{externalRef}}: only the properties the caller sent change, and only if the customer is
 * still the version the caller read. A null property means "leave this one alone", which is why clearing an attribute
 * is a {@code PUT} with a null body value rather than a patch; a field value is cleared by sending its key with null,
 * because the key is part of the value's name rather than of the property.
 */
public class PatchCustomerUseCase {

    /**
     * A change request: a null property is left as it is. Field values merge into the stored ones, and the tags to add
     * and remove are applied to the stored set instead of replacing it.
     */
    public record Change(
            String displayName,
            String phone,
            String email,
            Map<String, Object> fields,
            List<String> addTags,
            List<String> removeTags) {}

    private final UnitOfWork unitOfWork;
    private final CustomerRepository customers;
    private final CustomerFields fieldValues;
    private final Clock clock;
    private final int maxTags;

    public PatchCustomerUseCase(
            UnitOfWork unitOfWork,
            CustomerRepository customers,
            CustomerFields fieldValues,
            Clock clock,
            int maxTags) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
        this.fieldValues = fieldValues;
        this.clock = clock;
        this.maxTags = maxTags;
    }

    public Customer execute(String externalRef, Change change, ExpectedVersion expected) {
        String ref = CustomerRules.externalRef(externalRef);
        // A patch that changes nothing is not an error, but it is also not a change: no version bump, no announcement.
        String displayName = change.displayName() == null ? null : CustomerRules.displayName(change.displayName());
        String phone = change.phone() == null ? null : CustomerRules.phone(change.phone());
        String email = change.email() == null ? null : CustomerRules.email(change.email());
        return unitOfWork.transactional(() -> {
            Customer customer = customers
                    .lockByExternalRef(ref)
                    .orElseThrow(() -> new NotFoundException("No customer exists here with that reference."));
            if (customer.isErased()) {
                throw new CustomerErasedException();
            }
            expected.check(customer.version());
            String newPhone = phone == null ? customer.phone() : phone;
            String phoneDigits = phone == null ? customer.phoneDigits() : CustomerRules.phoneDigits(newPhone);
            Map<String, Object> fields = change.fields() == null
                    ? customer.fields()
                    : fieldValues.merging(customer.fields(), change.fields());
            boolean changed = customer.replace(
                    displayName == null ? customer.displayName() : displayName,
                    newPhone,
                    phoneDigits,
                    email == null ? customer.email() : email,
                    fields,
                    changedTags(customer, change),
                    clock.now());
            if (changed) {
                customers.update(customer);
            }
            return customer;
        });
    }

    /** The stored tag set with the additions and removals applied, so a tag the customer already carries is no change. */
    private TreeSet<String> changedTags(Customer customer, Change change) {
        TreeSet<String> tags = new TreeSet<>(customer.tags());
        for (String tag : TagRules.additions(change.addTags())) {
            tags.add(tag);
        }
        for (String tag : TagRules.additions(change.removeTags())) {
            tags.remove(tag);
        }
        TagRules.requireWithin(maxTags, tags.size());
        return tags;
    }
}
