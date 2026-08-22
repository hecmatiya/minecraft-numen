package com.dwinovo.numen.core.social;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴的"空闲模式"状态机(会话级,不落盘——重启回默认)。
 *
 * <p>三态:
 * <ul>
 *   <li><b>工作中</b>:有后台任务(LlmTaskChain 占用身体);</li>
 *   <li><b>空闲(陪伴)</b>:默认回落到 {@code company}——工作做完自动回到陪伴;</li>
 *   <li><b>空闲(挂机)</b>:什么都不做,只保留被动回应(社交链/生存链)。</li>
 * </ul>
 *
 * <p>挂机 ↔ 陪伴由对话切换:{@code companion_mode} 工具(MCP 或内置大脑都能调)。
 * 默认 {@link Mode#IDLE}(挂机)——不选就是挂机,选陪伴才跟随。
 */
public final class CompanionModes {

    public enum Mode { COMPANY, IDLE }

    /** 每同伴的当前空闲模式(没记录 = 默认挂机)。 */
    private static final Map<UUID, Mode> MODES = new HashMap<>();

    /** 任务刚结束的宽限(tick):身体闲下来后要连续闲这么久才自动回陪伴。
     *  给大脑一个重新派活的窗口——否则"任务一收工就秒速贴到主人身边",
     *  挖矿这种一件接一件的场景,观感就是"挖着挖着朝我走"。 */
    private static final int IDLE_GRACE_TICKS = 100;

    /** 每同伴最后一次"车道忙"的游戏刻——宽限期的起点(没记录 = 早就闲着)。 */
    private static final Map<UUID, Long> LAST_BUSY = new HashMap<>();

    private CompanionModes() {}

    public static Mode modeOf(UUID companionUuid) {
        return MODES.getOrDefault(companionUuid, Mode.IDLE);
    }

    public static void setMode(UUID companionUuid, Mode mode) {
        MODES.put(companionUuid, mode);
    }

    /**
     * 每 tick 的自动恢复:空闲模式 = 陪伴,且身体空闲(无任务)且没在陪
     * → 自动派发 company。工作结束的瞬间这里就会把它拉回陪伴。
     *
     * <p>挂机模式<b>不干预</b>任务:主人/大脑主动调 company 是明确意图,
     * 状态机不掐它(companion_mode 切到 idle 时才当场撤陪伴)。
     */
    public static void tickAutoRestore(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof NumenPlayer companion)) {
                continue;
            }
            UUID cid = companion.getUUID();
            // 车道有活(在跑或排队,同步异步都算) = 忙:刷新忙刻,绝不插陪伴任务。
            // 比旧的 asyncTaskFor==null 更严——排队中的下一件活也算忙,
            // 堵上"任务刚结束、新活还没起跑"的窗口。
            if (com.dwinovo.numen.task.CompanionTickDispatcher.llmLaneBusy(cid)) {
                LAST_BUSY.put(cid, now);
                continue;
            }
            if (modeOf(cid) == Mode.COMPANY
                    && now - LAST_BUSY.getOrDefault(cid, 0L) >= IDLE_GRACE_TICKS
                    && com.dwinovo.numen.task.CompanionTickDispatcher.asyncTaskFor(cid) == null) {
                com.dwinovo.numen.task.TaskDispatch.dispatchAsync(companion,
                        new com.dwinovo.numen.core.task.CompanyTaskRecord("restore", null, null),
                        msg -> { });
            }
        }
    }
}
