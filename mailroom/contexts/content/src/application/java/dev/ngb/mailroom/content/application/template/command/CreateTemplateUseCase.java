package dev.ngb.mailroom.content.application.template.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.content.application.template.port.TemplateRepository;
import dev.ngb.mailroom.content.domain.template.RenderLimits;
import dev.ngb.mailroom.content.domain.template.Template;
import dev.ngb.mailroom.content.domain.template.TemplatePolicy;
import dev.ngb.mailroom.content.domain.template.TemplateRevision;
import dev.ngb.mailroom.shared.TemplateKey;
import java.time.Instant;
import java.util.UUID;

/** Creates a template with its first immutable revision, refusing a hostile or oversize body before it is stored. */
public class CreateTemplateUseCase {

    public record Command(String key, String name, String subject, String html, String text) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final TemplateRepository templates;
    private final IdGenerator ids;
    private final Clock clock;
    private final RenderLimits limits;

    public CreateTemplateUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            TemplateRepository templates,
            IdGenerator ids,
            Clock clock,
            RenderLimits limits) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.templates = templates;
        this.ids = ids;
        this.clock = clock;
        this.limits = limits;
    }

    public Template execute(Command command) {
        TemplateKey key = new TemplateKey(command.key());
        TemplatePolicy.validateSubmission(command.subject(), command.html(), command.text(), limits);
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            Template template = Template.create(ids.newId(), tenantId, key, command.name(), now);
            TemplateRevision revision = new TemplateRevision(
                    ids.newId(), tenantId, template.id(), 1, command.subject(), command.html(), command.text(), now);
            templates.insert(template, revision);
            return template;
        });
    }
}
