package dev.ngb.mailroom.sending.application.settings.service;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.sending.application.email.port.EmailSettingsView;
import dev.ngb.mailroom.sending.application.settings.port.EmailSettingsRepository;

/** Reads the tenant's effective settings inside a tenant-scoped transaction. */
public class SettingsReader {

    private final UnitOfWork unitOfWork;
    private final EmailSettingsRepository settings;

    public SettingsReader(UnitOfWork unitOfWork, EmailSettingsRepository settings) {
        this.unitOfWork = unitOfWork;
        this.settings = settings;
    }

    public EmailSettingsView current() {
        return unitOfWork.transactional(settings::current);
    }
}
