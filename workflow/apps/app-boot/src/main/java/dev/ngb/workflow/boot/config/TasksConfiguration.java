package dev.ngb.workflow.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.platform.api.replica.UserDirectory;
import dev.ngb.workflow.runtime.api.task.TaskResumer;
import dev.ngb.workflow.tasks.adapter.task.persistence.JooqTaskGroupRepository;
import dev.ngb.workflow.tasks.adapter.task.persistence.JooqTaskRepository;
import dev.ngb.workflow.tasks.application.task.command.ClaimTaskUseCase;
import dev.ngb.workflow.tasks.application.task.command.CompleteTaskUseCase;
import dev.ngb.workflow.tasks.application.task.command.CreateTaskUseCase;
import dev.ngb.workflow.tasks.application.task.command.ManageTaskGroupUseCase;
import dev.ngb.workflow.tasks.application.task.command.ReassignTaskUseCase;
import dev.ngb.workflow.tasks.application.task.port.TaskGroupRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskTokenResumer;
import dev.ngb.workflow.tasks.adapter.task.persistence.JooqTaskReadRepository;
import dev.ngb.workflow.tasks.application.task.query.TaskQueries;
import dev.ngb.workflow.tasks.application.task.query.TaskReadRepository;
import dev.ngb.workflow.tasks.application.task.service.TaskSweep;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the tasks context: its stores, its use cases, the inbox and the deadlines. */
@Configuration(proxyBeanMethods = false)
class TasksConfiguration {

    @Bean
    TaskRepository taskRepository(DSLContext dsl, JsonMapper json) {
        return new JooqTaskRepository(dsl, json);
    }

    @Bean
    TaskGroupRepository taskGroupRepository(DSLContext dsl) {
        return new JooqTaskGroupRepository(dsl);
    }

    @Bean
    CreateTaskUseCase createTaskUseCase(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskGroupRepository groups,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateTaskUseCase(unitOfWork, tasks, groups, tenantContext, ids, clock);
    }

    @Bean
    ClaimTaskUseCase claimTaskUseCase(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            UserDirectory users,
            CurrentActor currentActor,
            Clock clock) {
        return new ClaimTaskUseCase(unitOfWork, tasks, users, currentActor, clock);
    }

    @Bean
    CompleteTaskUseCase completeTaskUseCase(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskTokenResumer resumer,
            UserDirectory users,
            CurrentActor currentActor,
            Clock clock) {
        return new CompleteTaskUseCase(unitOfWork, tasks, resumer, users, currentActor, clock);
    }

    @Bean
    ReassignTaskUseCase reassignTaskUseCase(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskGroupRepository groups,
            UserDirectory users,
            CurrentActor currentActor,
            Clock clock) {
        return new ReassignTaskUseCase(unitOfWork, tasks, groups, users, currentActor, clock);
    }

    @Bean
    ManageTaskGroupUseCase manageTaskGroupUseCase(
            UnitOfWork unitOfWork,
            TaskGroupRepository groups,
            TaskRepository tasks,
            UserDirectory users,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new ManageTaskGroupUseCase(unitOfWork, groups, tasks, users, tenantContext, ids, clock);
    }

    @Bean
    TaskReadRepository taskReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqTaskReadRepository(dsl, json);
    }

    @Bean
    TaskQueries taskQueries(
            UnitOfWork unitOfWork, TaskReadRepository tasks, UserDirectory users, CurrentActor currentActor) {
        return new TaskQueries(unitOfWork, tasks, users, currentActor);
    }

    @Bean
    TaskSweep taskSweep(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskGroupRepository groups,
            TaskTokenResumer resumer,
            Clock clock) {
        return new TaskSweep(unitOfWork, tasks, groups, resumer, clock);
    }

    /** The tasks context tells the runtime to resume a parked token; the call joins the task's own transaction. */
    @Bean
    TaskTokenResumer taskTokenResumer(TaskResumer resumer) {
        return (tokenId, branch, payload) -> resumer.resume(tokenId, branch, payload);
    }
}
