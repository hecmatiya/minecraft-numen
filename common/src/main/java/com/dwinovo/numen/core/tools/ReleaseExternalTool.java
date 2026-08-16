package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.task.TaskResult;

import java.util.Map;

/**
 * {@code release_external} —— 临时放行内置大脑(双脑模式):MCP 模式开着时
 * 内置大脑默认静默,调这个让它也响应主人的话,与外部大脑并行。想收回就调
 * {@code acquire_external}。纯客户端工具。
 */
public final class ReleaseExternalTool implements NumenTool {

    @Override
    public String name() {
        return "release_external";
    }

    @Override
    public String description() {
        return "TEMPORARILY let the companion's built-in brain respond again (dual-brain mode): "
                + "while the MCP server is on it stays silent by default — call this when you want "
                + "it to also answer the owner in-game, alongside you. Reassert silence with "
                + "acquire_external when done. No-op if it was already released.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void invoke(ToolCall call) {
        if (call.ctx().entityUuid() == null) {
            call.complete(TaskResult.fail("companion 不在当前加载范围。").toJson());
            return;
        }
        EntityAgentLoop loop = AgentLoopRegistry.get(call.ctx().entityUuid()).orElse(null);
        if (loop == null) {
            call.complete(TaskResult.fail("该同伴没有客户端大脑实例(没召唤?)。").toJson());
            return;
        }
        if (!loop.isExternallyDriven()) {
            call.complete(TaskResult.ok("本来就由内置大脑驱动,无需交还。").toJson());
            return;
        }
        loop.releaseExternal();
        call.complete(TaskResult.ok("已交还:内置大脑恢复,游戏内的话她会自己回应了。").toJson());
    }
}
