package dev.ngb.booking.catalog.adapter.block.persistence;

import static dev.ngb.booking_jooq.Tables.BLOCKS;

import dev.ngb.booking.catalog.application.block.port.BlockRepository;
import dev.ngb.booking.catalog.domain.block.Block;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Stores blocks. A one-off block writes the empty rule the table's own check pairs with its two instants; a repeating
 * one writes the lines it repeats.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here, so a missing tenant scope
 * reads nothing instead of reading every tenant's blocks.
 */
public class JooqBlockRepository implements BlockRepository {

    private final DSLContext dsl;

    public JooqBlockRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Block block) {
        dsl.insertInto(BLOCKS)
                .set(BLOCKS.TENANT_ID, block.tenantId().value())
                .set(BLOCKS.ID, block.id().value())
                .set(BLOCKS.RESOURCE_ID, block.resourceId())
                .set(BLOCKS.REASON, block.reason())
                .set(BLOCKS.STARTS_AT, block.startsAt())
                .set(BLOCKS.ENDS_AT, block.endsAt())
                .set(BLOCKS.RULE, block.rule().toArray(String[]::new))
                .set(BLOCKS.DURATION_MINUTES, block.durationMinutes())
                .set(BLOCKS.VERSION, block.version())
                .set(BLOCKS.CREATED_AT, block.createdAt())
                .set(BLOCKS.UPDATED_AT, block.updatedAt())
                .execute();
    }

    @Override
    public boolean delete(UUID blockId) {
        return dsl.deleteFrom(BLOCKS).where(BLOCKS.ID.eq(blockId)).execute() == 1;
    }
}
