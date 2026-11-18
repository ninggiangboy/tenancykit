package dev.ngb.customer.customers.adapter.tag.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.customer.customers.application.tag.query.TagListing;
import dev.ngb.customer.customers.application.tag.query.TagQueries;
import dev.ngb.customer.customers.application.tag.query.TagView;
import dev.ngb.customer.shared.CustomerPermissions;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tags the Tenant's customers carry: each distinct tag once, with the number of customers that carry it.
 *
 * <p>Tags are written with the customer — a {@code PUT} replaces them and a {@code PATCH} adds and removes them — so
 * this edge only reads what the customer endpoints stored.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class TagController {

    public record TagResponse(String tag, long customerCount) {

        static TagResponse of(TagView view) {
            return new TagResponse(view.tag(), view.customerCount());
        }
    }

    private final TagQueries tags;

    public TagController(TagQueries tags) {
        this.tags = tags;
    }

    @GetMapping("/tags")
    @Listing(TagListing.class)
    @RequiresPermission(CustomerPermissions.CUSTOMER_READ)
    public Page<TagResponse> listTags(ListQuery query) {
        return tags.list(query).map(TagResponse::of);
    }
}
