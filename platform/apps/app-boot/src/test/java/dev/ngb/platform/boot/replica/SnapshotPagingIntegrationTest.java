package dev.ngb.platform.boot.replica;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.spring.runtime.UuidV7IdGenerator;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The snapshot API pages a replica from zero, and a cursor past the end answers an empty, final page. */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class SnapshotPagingIntegrationTest {

    private static final int CREATED = 1_200;
    private static final int LIMIT = 500;

    @Autowired SnapshotController controller;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void everyTenantIsPagedExactlyOnceAndTheLastPageHasNoNext() {
        seedTenants();

        List<Map<String, Object>> items = new ArrayList<>();
        UUID cursor = null;
        int pages = 0;
        UUID last;
        while (true) {
            UUID pageCursor = cursor;
            Map<String, Object> page = onPlatform(() -> controller.page("tenants", pageCursor, LIMIT));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pageItems = (List<Map<String, Object>>) page.get("items");
            assertThat(pageItems).hasSizeLessThanOrEqualTo(LIMIT);
            items.addAll(pageItems);
            pages++;
            Object next = page.get("next");
            if (pageItems.isEmpty()) {
                assertThat(next).as("an empty page is the last one").isNull();
                break;
            }
            last = (UUID) pageItems.getLast().get("id");
            if (next == null) {
                cursor = last;
                break;
            }
            assertThat(pageItems).as("a full page has a cursor to continue from").hasSize(LIMIT);
            cursor = (UUID) next;
        }

        assertThat(items).extracting(item -> item.get("id")).doesNotHaveDuplicates();
        assertThat((long) items.size()).isEqualTo((long) owner.count("select count(*) from tenants"));
        assertThat(items.size()).isGreaterThanOrEqualTo(CREATED);
        assertThat(pages).isGreaterThanOrEqualTo(3);

        UUID pastEnd = (UUID) items.getLast().get("id");
        Map<String, Object> afterEnd = onPlatform(() -> controller.page("tenants", pastEnd, LIMIT));
        assertThat((List<?>) afterEnd.get("items")).isEmpty();
        assertThat(afterEnd.get("next")).isNull();
    }

    @Test
    void aLimitAboveFiveHundredIsClamped() {
        Map<String, Object> page = onPlatform(() -> controller.page("tenants", null, 10_000));

        assertThat((List<?>) page.get("items")).hasSizeLessThanOrEqualTo(500);
    }

    private void seedTenants() {
        UuidV7IdGenerator ids = new UuidV7IdGenerator();
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < CREATED; i++) {
            rows.add(new Object[] {ids.newId(), "seed" + suffix + i, "Seed " + i, now});
        }
        owner.jdbc().batchUpdate(
                "insert into tenants (id, code, name, status, created_at, version) values (?, ?, ?, 'ACTIVE', ?, 1)", rows);
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }
}
