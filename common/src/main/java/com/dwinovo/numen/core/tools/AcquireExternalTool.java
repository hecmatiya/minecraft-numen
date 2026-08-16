package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.task.TaskResult;

import java.util.Map;

/**
 * {@code acquire_external} —— 收回静默(默认状态):MCP 模式开着时内置大脑
 * 本来就静默,这个只把 {@code release_external} 放行过的双脑状态收回来
 * (abort 内置正在跑的回合)。
 *
 * <p>纯客户端工具(大脑实例在客户端),覆写 {@link #invoke} 直接执行。
 */
public final class AcquireExternalTool implements NumenTool {

    @Override
    public String name() {
        return "acquire_external";
    }

    @Override
    public String description() {
        return "Reassert silence over the companion's built-in brain: it stops responding on its "
                + "own again. This is the DEFAULT state while the MCP server is on — call this only "
                + "to undo a release_external (dual-brain) you did earlier. If you never released, "
                + "the brain is already silent and this is a no-op.";
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
        loop.acquireExternal();
        call.complete(TaskResult.ok("已接管:内置大脑暂停,游戏内的话我直接听。"
                + "想交还时调 release_external。").toJson());
    }
}
