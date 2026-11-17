package dev.ngb.workflow.tasks.application.task.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.platform.api.replica.UserDirectory;
import dev.ngb.workflow.platform.api.replica.UserView;
import dev.ngb.workflow.tasks.application.task.port.TaskGroupRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.domain.task.TaskGroup;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A Tenant's task groups. Members must be active users in the replica when they are added; a user disabled later loses
 * access to the tasks they could see without being removed from the group, because every action re-reads the replica.
 */
public class ManageTaskGroupUseCase {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

    public record CreateCommand(String code, String name, Set<UUID> members) {}

    public record UpdateCommand(String code, ExpectedVersion expected, String name, Set<UUID> members) {}

    private final UnitOfWork unitOfWork;
    private final TaskGroupRepository groups;
    private final TaskRepository tasks;
    private final UserDirectory users;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public ManageTaskGroupUseCase(
            UnitOfWork unitOfWork,
            TaskGroupRepository groups,
            TaskRepository tasks,
            UserDirectory users,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.groups = groups;
        this.tasks = tasks;
        this.users = users;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public TaskGroup create(CreateCommand command) {
        TenantId tenant = tenantContext.requireTenant();
        String code = code(command.code());
        String name = name(command.name());
        Set<UUID> members = activeMembers(tenant, command.members());
        return unitOfWork.transactional(() -> {
            if (groups.findByCode(code).isPresent()) {
                throw new ConflictException("a task group with code " + code + " already exists");
            }
            Instant now = clock.now();
            return groups.add(new TaskGroup(ids.newId(), tenant, code, name, now, now, 1), members);
        });
    }

    public TaskGroup update(UpdateCommand command) {
        TenantId tenant = tenantContext.requireTenant();
        String name = name(command.name());
        Set<UUID> members = activeMembers(tenant, command.members());
        return unitOfWork.transactional(() -> {
            TaskGroup group = groups
                    .lockByCode(code(command.code()))
                    .orElseThrow(() -> new dev.ngb.core.error.NotFoundException("No task group exists here."));
            command.expected().check(group.version());
            TaskGroup renamed = group.renamed(name, clock.now());
            groups.update(renamed, members);
            return renamed;
        });
    }

    public void delete(String rawCode) {
        unitOfWork.transactional(() -> {
            TaskGroup group = groups
                    .lockByCode(code(rawCode))
                    .orElseThrow(() -> new dev.ngb.core.error.NotFoundException("No task group exists here."));
            if (tasks.tasksUsing(group.id()) > 0) {
                throw new ConflictException("the task group still has tasks; reassign or finish them first");
            }
            groups.delete(group.id());
            return null;
        });
    }

    public java.util.List<TaskGroup> all() {
        return groups.all();
    }

    /** The live members of a group, for the API's list view. */
    public java.util.Set<UUID> membersOf(TaskGroup group) {
        return groups.members(group.id());
    }

    private Set<UUID> activeMembers(TenantId tenant, Set<UUID> members) {
        Set<UUID> checked = new LinkedHashSet<>();
        if (members == null) {
            return checked;
        }
        for (UUID id : members) {
            UserView user = users.user(id).orElse(null);
            if (user == null || !user.active() || !user.tenantId().equals(tenant)) {
                throw new BadRequestException("user " + id + " is not an active user of this tenant");
            }
            checked.add(id);
        }
        return checked;
    }

    private static String code(String raw) {
        String code = raw == null ? "" : raw.strip();
        if (!CODE.matcher(code).matches()) {
            throw new BadRequestException(
                    "a task group code starts with a letter and holds lower-case letters, digits, '_' or '-'");
        }
        return code;
    }

    private static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 200) {
            throw new BadRequestException("a task group name is 1 to 200 characters");
        }
        return name;
    }
}
