package dev.ngb.workflow.boot.config;

import dev.ngb.workflow.runtime.application.runtime.port.TaskGateway;
import dev.ngb.workflow.tasks.application.task.command.CreateTaskUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one seam between the engine and the tasks context: the engine says what the node asked for, and this adapter
 * creates the task through the use case. A refusal becomes a failed step, never an exception that rolls back a claim.
 */
@Configuration(proxyBeanMethods = false)
class TaskGatewayConfiguration {

    @Bean
    TaskGateway taskGateway(CreateTaskUseCase createTask) {
        return draft -> {
            try {
                return TaskGateway.Result.created(createTask.execute(new CreateTaskUseCase.Command(
                        draft.instanceId(),
                        draft.tokenId(),
                        draft.nodeId(),
                        draft.title(),
                        draft.form(),
                        draft.assigneeUser(),
                        draft.assigneeGroup(),
                        draft.dueAt(),
                        draft.escalationGroup(),
                        draft.escalateAt(),
                        draft.expiresAt(),
                        draft.outcomes())));
            } catch (RuntimeException refusal) {
                String reason = refusal.getMessage() == null ? "task_not_creatable" : refusal.getMessage();
                return TaskGateway.Result.failed(reason);
            }
        };
    }
}
