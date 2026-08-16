package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.social.SocialMotion;
import com.dwinovo.numen.core.social.SocialReactions;
import com.dwinovo.numen.core.social.SocialReactions.Motion;
import com.dwinovo.numen.core.social.SocialSignals;
import com.dwinovo.numen.core.social.SocialSignals.Kind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.task.reflex.ReflexRegistry;
import net.minecraft.server.level.ServerPlayer;

/**
 * 社交反射链——SoulCraft 概率反应表的移植:主人在旁边做任何动作(14 种信号),
 * 她<b>按概率</b>挑一个反应——同样的情境,每次反应不同,这就是"活人感"。
 *
 * <p>决策表与动作执行在 {@link SocialReactions}(与陪伴任务共用);本链只负责
 * 竞拍调度:主人 6 格内 + 有信号 → 出价,动作做完回休眠。
 *
 * <p>出价在 LLM 基准价之下(比「说话看人」还低一档):任务在跑时自然让位,
 * 生存链更是随时抢得走——它只填<b>空闲</b>的空档。任务运行中的社交回应
 * 由 {@code CompanyCompanionTask} 内嵌处理。
 */
public final class SocialChain implements TaskChain, Reflex {

    /** 比「说话看人」(-1.0)再低一档——社交动作是最后顺位的表现层。 */
    private static final float PRIORITY = TaskChain.LLM_BASE_PRIORITY - 2.0f;

    /** 主人在这个距离内才会回应互动(格)。远了装没看见。 */
    private static final double RESPONSE_RANGE = 6.0;

    /** 当前正在做的动作;null = 休眠。 */
    private Motion current;
    private int ticksLeft;

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!ReflexRegistry.enabled(id())) {
            return Float.NEGATIVE_INFINITY;
        }
        if (current != null) {
            return PRIORITY;   // 动作还没做完,继续做完
        }
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null || owner.level() != companion.level()
                || companion.distanceToSqr(owner) > RESPONSE_RANGE * RESPONSE_RANGE) {
            return Float.NEGATIVE_INFINITY;   // 主人不在 6 格内,不互动
        }
        long now = companion.level().getGameTime();
        return SocialSignals.hasFresh(companion.getUUID(), now)
                ? PRIORITY
                : Float.NEGATIVE_INFINITY;
    }

    @Override
    public void tick(NumenPlayer companion) {
        long now = companion.level().getGameTime();
        // 平滑转头每 tick 驱动(有目标才动,无目标空转)。
        SocialMotion.tickLook(companion);
        if (current == null) {
            Kind kind = SocialSignals.consumeReaction(companion.getUUID(), now);
            if (kind == null) {
                return;
            }
            current = SocialReactions.pickReaction(kind, companion);
            ticksLeft = SocialReactions.duration(current);
            SocialReactions.startMotion(companion, current);
        }
        // 扫视两段式:过半切到右侧。
        if (current == Motion.HEAD_SCAN && ticksLeft == SocialReactions.duration(Motion.HEAD_SCAN) / 2) {
            SocialReactions.scanSecondHalf(companion);
        }
        if (--ticksLeft <= 0) {
            SocialReactions.finishMotion(companion, current);
            current = null;
        }
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        // 被生存链/任务抢走:收尾蹲姿,朝向不强行复位(自然)。
        if (current != null) {
            SocialReactions.finishMotion(companion, current);
        }
        current = null;
    }

    @Override
    public String name() {
        return "social";
    }

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "主人在旁边做任何动作时,会按概率做出不同的拟人小动作回应";
    }
}
