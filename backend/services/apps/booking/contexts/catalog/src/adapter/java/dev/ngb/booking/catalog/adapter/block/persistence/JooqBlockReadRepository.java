package dev.ngb.booking.catalog.adapter.block.persistence;

import static dev.ngb.booking_jooq.Tables.BLOCKS;

import dev.ngb.booking.catalog.application.block.query.BlockReadRepository;
import dev.ngb.booking.catalog.application.block.query.BlockView;
import dev.ngb.booking_jooq.tables.records.BlocksRecord;
import java.util.Arrays;
import java.util.List;
import org.jooq.DSLContext;

/** Selects blocks straight into the view, newest first; row-level security narrows every statement to the tenant. */
public class JooqBlockReadRepository implements BlockReadRepository {

    private final DSLContext dsl;

    public JooqBlockReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<BlockView> all() {
        return dsl.selectFrom(BLOCKS)
                .orderBy(BLOCKS.CREATED_AT.desc(), BLOCKS.ID.desc())
                .fetch(JooqBlockReadRepository::toView);
    }

    private static BlockView toView(BlocksRecord row) {
        String[] rule = row.getRule();
        return new BlockView(
                row.getId(),
                row.getResourceId(),
                row.getReason(),
                row.getStartsAt(),
                row.getEndsAt(),
                rule == null ? List.of() : Arrays.asList(rule));
    }
}
