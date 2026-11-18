package dev.ngb.booking.catalog.application.block.port;

import dev.ngb.booking.catalog.domain.block.Block;
import java.util.UUID;

/** Stores blocks. Every statement is tenant-scoped by row-level security, not by a condition here. */
public interface BlockRepository {

    void insert(Block block);

    /** @return whether a block of this tenant carried the id and was removed */
    boolean delete(UUID blockId);
}
