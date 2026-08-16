package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.landmark.LandmarkRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/**
 * {@code unmark_landmark} —— 忘掉一个命名地标。纯客户端工具。
 */
public final class UnmarkLandmarkTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String name) {}

    @Override
    public String name() {
        return "unmark_landmark";
    }

    @Override
    public String description() {
        return "Forget a named landmark — it stops being injected into turns. "
                + "Opposite of mark_landmark.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("name", "The landmark name to forget, e.g. 家/田/牛圈.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        Args a;
        try {
            a = GSON.fromJson(call.args(), Args.class);
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail("invalid arguments: " + ex.getMessage()).toJson());
            return;
        }
        if (a == null || a.name() == null || a.name().isBlank()) {
            call.complete(TaskResult.fail("name 必填:要忘掉哪个地标?").toJson());
            return;
        }
        boolean removed = LandmarkRegistry.remove(call.ctx().entityUuid(), a.name());
        call.complete(TaskResult.ok(removed
                ? "已忘掉地标 \"" + a.name().strip() + "\"。"
                : "没有叫 \"" + a.name().strip() + "\" 的地标。",
                Map.of("removed", removed)).toJson());
    }
}
