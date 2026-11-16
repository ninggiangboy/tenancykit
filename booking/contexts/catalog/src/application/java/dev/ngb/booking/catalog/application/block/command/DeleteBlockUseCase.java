package dev.ngb.booking.catalog.application.block.command;

import dev.ngb.booking.catalog.application.block.port.BlockRepository;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Removes a block, which reopens the period it closed. */
public class DeleteBlockUseCase {

    private final UnitOfWork unitOfWork;
    private final BlockRepository blocks;

    public DeleteBlockUseCase(UnitOfWork unitOfWork, BlockRepository blocks) {
        this.unitOfWork = unitOfWork;
        this.blocks = blocks;
    }

    public void execute(UUID blockId) {
        unitOfWork.transactional(() -> {
            if (!blocks.delete(blockId)) {
                throw new NotFoundException("No block exists here with that id.");
            }
        });
    }
}
