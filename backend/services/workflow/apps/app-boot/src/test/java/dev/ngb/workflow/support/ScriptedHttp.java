package dev.ngb.workflow.support;

import dev.ngb.workflow.runtime.domain.step.OutboundHttp;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The outbound door a test scripts: the answers it queues come back in order, every call is recorded, and a call with no
 * queued answer succeeds so a test only has to name the calls that matter.
 */
public class ScriptedHttp implements OutboundHttp {

    private final Queue<Result> answers = new ConcurrentLinkedQueue<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public ScriptedHttp answer(Result... results) {
        answers.addAll(List.of(results));
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<String> paths() {
        return calls.stream().map(Call::path).toList();
    }

    public void reset() {
        answers.clear();
        calls.clear();
    }

    public static Result ok(String body) {
        return new Result(200, Map.of("Content-Type", List.of("application/json")), body, null);
    }

    public static Result status(int status) {
        return new Result(status, Map.of(), "{\"status\":" + status + "}", null);
    }

    public static Result refused(String error) {
        return new Result(0, Map.of(), null, error);
    }

    @Override
    public Result send(Call call) {
        calls.add(call);
        Result answer = answers.poll();
        return answer == null ? ok("{}") : answer;
    }
}
