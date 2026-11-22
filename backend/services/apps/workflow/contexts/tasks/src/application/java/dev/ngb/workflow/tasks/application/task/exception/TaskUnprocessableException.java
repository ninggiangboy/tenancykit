package dev.ngb.workflow.tasks.application.task.exception;

/** A decision or an output the task cannot accept: the request is understood and must be changed. */
public class TaskUnprocessableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TaskUnprocessableException(String message) {
        super(message);
    }
}
