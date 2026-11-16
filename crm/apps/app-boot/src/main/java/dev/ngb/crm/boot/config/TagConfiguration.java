package dev.ngb.crm.boot.config;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.customers.adapter.tag.persistence.JooqTagReadRepository;
import dev.ngb.crm.customers.application.tag.query.TagQueries;
import dev.ngb.crm.customers.application.tag.query.TagReadRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the tag feature: the write side is the customer's own repository, because a customer's tags are part of what a
 * caller states about it, so only the listing has a repository of its own.
 */
@Configuration(proxyBeanMethods = false)
class TagConfiguration {

    @Bean
    TagReadRepository tagReadRepository(DSLContext dsl) {
        return new JooqTagReadRepository(dsl);
    }

    @Bean
    TagQueries tagQueries(UnitOfWork unitOfWork, TagReadRepository tags) {
        return new TagQueries(unitOfWork, tags);
    }
}
