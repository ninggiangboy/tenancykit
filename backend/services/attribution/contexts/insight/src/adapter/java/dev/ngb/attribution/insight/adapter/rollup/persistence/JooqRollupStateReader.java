package dev.ngb.attribution.insight.adapter.rollup.persistence;

import static dev.ngb.attribution_jooq.Tables.ROLLUP_CURSOR;

import dev.ngb.attribution.insight.api.rollup.RollupStateReader;
import java.time.Instant;
import java.time.LocalDate;
import org.jooq.DSLContext;

/** How far the rollups have reached, read from the one cursor row. */
public class JooqRollupStateReader implements RollupStateReader {

    private final DSLContext dsl;

    public JooqRollupStateReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public LocalDate rolledUpThrough() {
        return dsl.select(ROLLUP_CURSOR.ROLLED_UP_THROUGH)
                .from(ROLLUP_CURSOR)
                .where(ROLLUP_CURSOR.ID.eq((short) 1))
                .fetchOne(ROLLUP_CURSOR.ROLLED_UP_THROUGH);
    }

    @Override
    public Instant dataThrough() {
        return dsl.select(ROLLUP_CURSOR.DATA_THROUGH)
                .from(ROLLUP_CURSOR)
                .where(ROLLUP_CURSOR.ID.eq((short) 1))
                .fetchOne(ROLLUP_CURSOR.DATA_THROUGH);
    }
}
