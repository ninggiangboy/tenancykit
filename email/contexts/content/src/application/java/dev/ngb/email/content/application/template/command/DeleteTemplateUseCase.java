package dev.ngb.email.content.application.template.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.content.application.template.port.TemplateRepository;
import dev.ngb.email.content.domain.template.Template;
import java.util.UUID;

/** Removes the pointer; the immutable revisions stay so a campaign that pinned one still renders. */
public class DeleteTemplateUseCase {

    private final UnitOfWork unitOfWork;
    private final TemplateRepository templates;

    public DeleteTemplateUseCase(UnitOfWork unitOfWork, TemplateRepository templates) {
        this.unitOfWork = unitOfWork;
        this.templates = templates;
    }

    public void execute(UUID templateId, ExpectedVersion expected) {
        unitOfWork.transactional(() -> {
            Template current = templates.findById(templateId)
                    .orElseThrow(() -> new NotFoundException("No template exists here."));
            expected.check(current.version());
            if (!templates.delete(templateId, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return null;
        });
    }
}
