package dev.ngb.platform.tenancy.application.uiremote.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteReadRepository;
import java.util.Optional;

/** Read side of the registry for Platform administrators (Platform scope). */
public class UiRemoteQueries {

    private final UnitOfWork unitOfWork;
    private final UiRemoteReadRepository remotes;

    public UiRemoteQueries(UnitOfWork unitOfWork, UiRemoteReadRepository remotes) {
        this.unitOfWork = unitOfWork;
        this.remotes = remotes;
    }

    public Page<UiRemoteView> list(ListQuery query) {
        return unitOfWork.transactional(() -> remotes.list(query));
    }

    public Optional<UiRemoteView> byName(String name) {
        return unitOfWork.transactional(() -> remotes.find(name));
    }
}
