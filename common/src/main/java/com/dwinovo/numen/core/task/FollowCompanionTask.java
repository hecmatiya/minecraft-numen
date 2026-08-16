package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 跟着走——<b>常驻</b>任务。默认跟主人,点名了就跟那一只。
 *
 * <p>导航用 {@link PlayerNav#followEntity}:每 tick 用实体的<b>当前脚位</b>
 * 重新校验目标——主人边走她跟得上,跑远了也不丢(1.21.1 移植版曾用裸目标
 * {@code toGoal},目标只在重规划时冻结取样,主人跑远后导航还奔着旧点,
 * 距离越拉越大直至 FAILED 退避,表现为"离得远了跟不上")。
 *
 * <h2>它跟一次性任务差在哪</h2>
 * 只差一行:{@link #onTick} <b>永远不返终态</b>。同一个槽、同一套派发、同一个接口,
 * 「挖 64 块」干完腾位,而它一直占着,直到主人给她别的事做。
 *
 * <h2>跟到了就休眠,不是结束</h2>
 * 主人就在旁边时 {@link #onTick} 不建导航、不动身体:她站着看你,生存链饿了要
 * 吃东西自然把她抢走;主人一走远(超出 keepWithin + 迟滞)它自己醒过来重建导航。
 * 这跟原版 {@code Goal.canUse()} 是同一个道理——<b>休眠不是失败</b>,不发结果、
 * 不腾槽、不惊动模型。(1.21.1 用 {@code canRun} 表达休眠,26.1.2 重构后基类
 * 没有这个钩子,让位由生存链抢占承担,这里改为 onTick 内的迟滞守卫。)
 *
 * <h2>够不着也是休眠</h2>
 * 常驻任务没有"失败"这个终点,所以「够不着」只能表达成休眠 + 退避重试:主人飞起来、
 * 隔着断崖、换了维度,她就先放手,等条件回来自己醒。
 *
 * <h2>目标没了,主人和别人不一样</h2>
 * <b>主人下线是暂时的</b>——他会回来,所以休眠等着,这也是常驻该有的样子。而点名跟的
 * 那只羊死了、或者走出加载范围被卸载了,再等也不会回来:那时收尾报给模型,让它决定下
 * 一步。一套逻辑通吃的话,要么她对着一只死羊站到天荒地老,要么主人一下线任务就没了。
 *
 * <p><b>{@code nav.tick()} 的返回值一个都不能丢</b>:{@link PlayerNav} 的 FAILED 是
 * <b>终局闩</b>(一经裁定即稳定持续)。主人一飞起来导航就判 NO_PATH,此后每一刻都返
 * FAILED——没人接住并重建的话,她<b>永久定在原地</b>,主人落回地面也不会自愈。而
 * {@code task_status} 照样说"执行中"(常驻任务按定义永远 RUNNING),主人完全看不出
 * 她卡住了。
 */
public final class FollowCompanionTask extends AbstractCompanionTask<FollowTaskRecord> {

    private static final double WALK_SPEED = 1.0;
    /** 比 {@code keepWithin} 多出这么远才重新起步,免得在临界距离上抖着走走停停。 */
    private static final double RESUME_MARGIN = 2.0;

    /** 够不着之后第一次重试等多久(刻)。 */
    private static final int RETRY_BASE_TICKS = 20;
    /** 连续够不着时退避上限(刻)。一次失败的搜索会烧光整个预算,主人飞五分钟不该重算三百次。 */
    private static final int RETRY_MAX_TICKS = 200;

    /** 够不着时休眠到这个游戏刻;0 = 没在退避。 */
    private long retryAtGameTime;
    /** 连续够不着的次数,只用来算退避时长。 */
    private int unreachableStreak;

    public FollowCompanionTask(NumenPlayer player, FollowTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        retryAtGameTime = 0;
        unreachableStreak = 0;
    }

    @Override
    protected TaskState onTick() {
        Entity target = target(player);
        if (target == null) {
            if (r.entityId == null) {
                return TaskState.RUNNING;   // 主人下线:休眠等他回来(每刻轻量检查即是"休眠")
            }
            stopNav();
            fail("the entity you were following is gone (killed, or it left the loaded area)",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (player.level().getGameTime() < retryAtGameTime) {
            return TaskState.RUNNING;   // 刚判过够不着,退避中——不建导航,别空转烧搜索预算
        }
        double gap = player.position().distanceTo(target.position());
        if (nav == null) {
            // 迟滞:走出 keepWithin + margin 才重新起步,回到 keepWithin 之内就停——
            // 单阈值会让她在临界距离上一步一停地抖。跟到了就不建导航,身体闲着
            // (26.1.2 里让位由生存链抢占承担,这里只管"不走")。
            if (gap <= r.keepWithin + RESUME_MARGIN) {
                return TaskState.RUNNING;
            }
            // followEntity:每 tick 用实体当前脚位重校验目标,主人跑远也不丢。
            nav = PlayerNav.followEntity(player, () -> target(player),
                    r.keepWithin, WALK_SPEED, this::closeEnough);
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> {
                stopNav();
                unreachableStreak = 0;
            }
            case FAILED -> backOff();
        }
        if (closeEnough()) {
            stopNav();
            unreachableStreak = 0;
        }
        // 永远不返终态 —— 这一行就是"常驻"的全部含义。
        return TaskState.RUNNING;
    }

    /**
     * 现在够不着:拆掉导航(FAILED 是终局闩,不拆就永远失败)、放手、退避一会儿再试。
     * 退避按连续失败次数翻倍到上限——主人在天上飞的那几分钟里,每秒重算一次全预算
     * 搜索是纯浪费,而她真落地时最多晚 10 秒就跟上。
     */
    private void backOff() {
        stopNav();
        unreachableStreak++;
        int wait = Math.min(RETRY_MAX_TICKS, RETRY_BASE_TICKS << Math.min(unreachableStreak - 1, 4));
        retryAtGameTime = player.level().getGameTime() + wait;
    }

    /**
     * 跟着谁。没点名就是主人;点名了就按 id 现查——每次都查,因为它随时可能死掉或者
     * 走出加载范围,而那两件事对我们是同一个答案:不在了。
     *
     * <p>不同维度天然落进 null:{@code ServerLevel.getEntity} 只认自己这一层。
     */
    private Entity target(NumenPlayer companion) {
        if (r.entityId == null) {
            var owner = companion.resolveOwnerPlayer();
            return owner == null || owner.level() != companion.level() ? null : owner;
        }
        Entity e = ((ServerLevel) companion.level()).getEntity(r.entityId);
        if (e == null || e.isRemoved() || e == companion) {
            return null;
        }
        // id 对上还不够:重启之后同一个号可能发给了别的东西。
        return r.targetUuid != null && !r.targetUuid.equals(e.getUUID()) ? null : e;
    }

    private boolean closeEnough() {
        Entity target = target(player);
        return target != null && player.position().distanceTo(target.position()) <= r.keepWithin;
    }

    @Override
    protected String successMessage() {
        // 常驻任务走不到 SUCCESS;真被换掉时走的是 cancelledMessage。
        return "跟着主人";
    }

    @Override
    protected String cancelledMessage() {
        String who = r.entityId == null ? "主人" : "实体 " + r.entityId;
        return "不再跟着" + who + "了";
    }
}
