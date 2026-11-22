-- A join that waits for the first or the nth branch abandons the branches still moving, so a token needs a state that
-- is not a completion: CANCELLED writes no successor and is never claimed.
alter table workflow_token drop constraint if exists workflow_token_status_check;
alter table workflow_token
    add constraint workflow_token_status_check
        check (status in ('READY', 'RUNNING', 'WAITING', 'DONE', 'FAILED', 'CANCELLED'));
