package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.social.CompanionModes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 空闲模式切换工具——挂机 ↔ 陪伴,当场生效:
 *
 * <ul>
 *   <li>{@code company}:空闲时自动处于陪伴状态(跟随/闲逛/护卫/社交),
 *       工作做完自动回到陪伴;</li>
 *   <li>{@code idle}:空闲时什么都不做(只保留被动回应),工作做完回到挂机。</li>
 * </ul>
 *
 * <p>内置大脑和 MCP 都能调——"挂机还是陪伴"由对话决定。
 */
public final class CompanionModeTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String mode) {}

    @Override
    public String name() {
        return "companion_mode";
    }

    @Override
    public String description() {
        return "Switch what you do when idle — the standing state you fall back to whenever "
                + "you finish a job. 'company': when idle you keep the owner company (follow at "
                + "a distance, wander beside them, guard against nearby mobs, answer their "
                + "gestures); after any job you automatically return to company. 'idle': when "
                + "idle you do nothing (still answer gestures nearby); after any job you return "
                + "to idle. Takes effect immediately: switching to idle stops an active company "
                + "job, switching to company starts one. Default is idle — choose company when "
                + "you want to be around the owner whenever nothing else is going on.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("mode", "company(陪伴) or idle(挂机)。")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a == null || a.mode() == null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                    "mode 必填:company(陪伴)或 idle(挂机)。当前是 "
                            + CompanionModes.modeOf(companion.getUUID()).name().toLowerCase()
                            + "。").toJson());
            return;
        }
        String mode = a.mode().strip().toLowerCase(java.util.Locale.ROOT);
        CompanionModes.Mode parsed = switch (mode) {
            case "company" -> CompanionModes.Mode.COMPANY;
            case "idle" -> CompanionModes.Mode.IDLE;
            default -> null;
        };
        if (parsed == null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                    "mode 只能是 company 或 idle,收到:" + mode + "。").toJson());
            return;
        }
        CompanionModes.setMode(companion.getUUID(), parsed);
        String what;
        if (parsed == CompanionModes.Mode.COMPANY) {
            what = "空闲时会陪在主人身边(跟随/闲逛/护卫),活干完自动回到陪伴。";
        } else {
            // 切挂机:当场撤掉正在跑的陪伴任务(状态机只在切换这一刻干预)。
            var active = com.dwinovo.numen.task.CompanionTickDispatcher.asyncTaskFor(
                    companion.getUUID());
            if (active != null && com.dwinovo.numen.core.task.CompanyTaskRecord.TOOL_NAME
                    .equals(active.getToolName())) {
                com.dwinovo.numen.task.CompanionTickDispatcher.stopActive(companion,
                        "mode switched to idle");
            }
            what = "空闲时什么都不做,只保留被动回应;活干完回到挂机。";
        }
        reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                "已切到 " + parsed.name().toLowerCase() + " 模式。" + what,
                Map.of("mode", parsed.name().toLowerCase())).toJson());
    }
}
