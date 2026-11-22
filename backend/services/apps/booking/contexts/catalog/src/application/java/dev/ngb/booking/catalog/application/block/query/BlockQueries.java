package dev.ngb.booking.catalog.application.block.query;

import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;

/** Reads the current tenant's blocks for the GET handler. */
public class BlockQueries {

    private final UnitOfWork unitOfWork;
    private final BlockReadRepository blocks;

    public BlockQueries(UnitOfWork unitOfWork, BlockReadRepository blocks) {
        this.unitOfWork = unitOfWork;
        this.blocks = blocks;
    }

    public List<BlockView> list() {
        return unitOfWork.transactional(() -> blocks.all());
    }
}
