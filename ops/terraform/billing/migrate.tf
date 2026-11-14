# The migrate runtime is a task definition, not a service: ECS has no `depends_on: service_completed_successfully`,
# so the pipeline runs it with `aws ecs run-task` and waits for STOPPED with exit code 0 before forcing the api and
# worker services onto the new revision. Flyway is versioned, so running it on every deploy is safe.
#
# The task definition is exported for that pipeline; nothing here runs it.
output "migrate_task_definition_arn" {
  value = aws_ecs_task_definition.migrate.arn
}
