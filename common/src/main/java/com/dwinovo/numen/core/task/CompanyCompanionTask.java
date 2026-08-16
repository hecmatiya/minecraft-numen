package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.social.SocialMotion;
import com.dwinovo.numen.core.social.SocialReactions;
import com.dwinovo.numen.core.social.SocialReactions.Motion;
import com.dwinovo.numen.core.social.SocialSignals;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * 「陪着」——follow 的松散版常驻任务。同一份"目标 = 主人/点名实体",
 * 三种表现按距离切换:
 *
 * <ul>
 *   <li><b>远(&gt; {@value #CHASE_RADIUS} 格)</b>:像 follow 一样跟过去
 *       ({@link PlayerNav#followEntity},每 tick 重校验目标,跑远不丢);</li>
 *   <li><b>近</b>:在身边闲逛——随机点走动(3~8 格绕圈),到点歇一会再走,
 *       时不时看向主人;</li>
 *   <li><b>社交</b>:主人 6 格内且有动作信号时,执行概率反应
 *       ({@link SocialReactions}),动作做完继续闲逛——<b>不打断任务</b>。</li>
 * </ul>
 *
 * <p>常驻语义与 {@link FollowCompanionTask} 相同:不返终态,被别的任务顶掉
 * 才结束;主人下线休眠等他回来,点名目标没了才报失败。社交动作执行期间
 * 导航保留(不销毁),动作做完接着走。
 */
public final class CompanyCompanionTask extends AbstractCompanionTask<CompanyTaskRecord> {

    /** 比这个远就进入跟随;回来到这个距离内开始闲逛(迟滞半径)。 */
    private static final double CHASE_RADIUS = 12.0;
    /** 闲逛绕圈的半径范围(格,围绕目标)。 */
    private static final double LOITER_MIN = 3.0;
    private static final double LOITER_MAX = 8.0;
    /** 到一个闲逛点后歇多久(刻)。 */
    private static final int REST_TICKS = 60;
    /** 闲逛点走不到后的退避(刻)。 */
    private static final int FAIL_BACKOFF_TICKS = 40;
    /** 闲逛时偶尔看向主人的间隔(刻)。 */
    private static final int GAZE_EVERY_TICKS = 120;
    /** 社交响应范围(格)——与 SocialChain 一致。 */
    private static final double SOCIAL_RANGE = 6.0;

    // ---- 护卫(近距离有怪自动出击) ----
    /** 这个距离内的怪物会被自动清理(护卫职责)。 */
    private static final double COMBAT_SCAN_RADIUS = 8.0;
    /** 追出这个距离就放弃(不追出国境)。 */
    private static final double COMBAT_DROP_RADIUS = 16.0;
    /** 原生近战攻击距离。 */
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQR = ATTACK_REACH * ATTACK_REACH;
    private static final double COMBAT_SPEED = 1.2;
    /** 战斗扫描间隔(刻)——每 10 刻扫一次近处怪物。 */
    private static final int COMBAT_SCAN_INTERVAL = 10;
    /** 追不上几次就放弃这个目标一段时间。 */
    private static final int COMBAT_FAIL_LIMIT = 3;
    /** 放弃目标后忽略它的时长(刻)。 */
    private static final int COMBAT_IGNORE_TICKS = 200;

    private static final double WALK_SPEED = 1.0;

    private PlayerNav nav;
    /** 当前导航是不是"跟随"导航(远)还是"闲逛"导航(近)——切换时按此销毁重建。 */
    private boolean chasing;
    /** 正在执行的社交动作;null = 没在社交。 */
    private Motion social;
    private int socialTicksLeft;
    /** 歇到这一刻再走下一段;0 = 可以走。 */
    private long restUntilGameTime;
    private long nextGazeGameTime;
    /** 连续走不到的次数(退避用)。 */
    private int unreachableStreak;

    // ---- 战斗状态 ----
    private LivingEntity combatTarget;
    private PlayerNav combatNav;
    private int combatFails;
    private long nextCombatScan;
    /** 追不上被放弃的目标(id → 忽略到这一刻)。 */
    private final java.util.Map<Integer, Long> combatUnreachable = new java.util.HashMap<>();

    public CompanyCompanionTask(NumenPlayer player, CompanyTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        chasing = false;
        social = null;
        restUntilGameTime = 0;
        nextGazeGameTime = 0;
        unreachableStreak = 0;
        combatTarget = null;
        combatNav = null;
        combatFails = 0;
        nextCombatScan = 0;
    }

    @Override
    protected TaskState onTick() {
        Entity target = target(player);
        if (target == null) {
            if (r.entityId == null) {
                return TaskState.RUNNING;   // 主人下线:休眠等他回来
            }
            stopNav();
            fail("the entity you were keeping company with is gone (killed, or it left the loaded area)",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        long now = player.level().getGameTime();

        // 护卫优先:近距离有怪先打(威胁大于一切)。
        if (tickCombat(now)) {
            return TaskState.RUNNING;
        }

        // 社交其次:主人 6 格内 + 有信号 → 概率反应;动作做完继续陪。
        if (tickSocial(target, now)) {
            return TaskState.RUNNING;
        }

        double gap = player.position().distanceTo(target.position());
        if (gap > CHASE_RADIUS) {
            // 远:跟随(与 follow 同一导航,每 tick 重校验目标)。
            if (!chasing || nav == null) {
                stopNav();
                nav = PlayerNav.followEntity(player, () -> target(player),
                        CHASE_RADIUS - 2.0, WALK_SPEED, () -> false);
                chasing = true;
            }
            switch (nav.tick()) {
                case RUNNING -> { }
                case ARRIVED, FAILED -> stopNav();
            }
        } else {
            // 近:闲逛。只有从"跟随"切过来才销毁导航;闲逛导航持续推进。
            if (chasing) {
                stopNav();
                chasing = false;
                restUntilGameTime = now + REST_TICKS;   // 刚到身边,先歇口气
            }
            if (now < restUntilGameTime) {
                maybeGaze(target, now);
                return TaskState.RUNNING;   // 歇着
            }
            if (nav == null) {
                NavGoal goal = pickLoiterGoal(target, now);
                if (goal != null) {
                    unreachableStreak = 0;
                    nav = PlayerNav.toGoal(player, () -> goal, WALK_SPEED,
                            () -> goal.isAt(player.blockPosition()));
                } else {
                    restUntilGameTime = now + FAIL_BACKOFF_TICKS;
                }
            }
            if (nav != null) {
                switch (nav.tick()) {
                    case RUNNING -> { }
                    case ARRIVED -> {
                        stopNav();
                        restUntilGameTime = now + REST_TICKS;
                    }
                    case FAILED -> {
                        stopNav();
                        restUntilGameTime = now + FAIL_BACKOFF_TICKS;
                    }
                }
            }
            maybeGaze(target, now);
        }
        return TaskState.RUNNING;
    }

    // ---- 护卫(近距离有怪自动出击) ----

    /**
     * 战斗一帧。返回 true = 本帧被战斗占用(在追或在打)。
     * 打完了/追丢了回 false,陪的模式继续。
     */
    private boolean tickCombat(long now) {
        if (combatTarget != null && (combatTarget.isRemoved() || !combatTarget.isAlive()
                || player.distanceToSqr(combatTarget) > COMBAT_DROP_RADIUS * COMBAT_DROP_RADIUS
                || combatUnreachable.getOrDefault(combatTarget.getId(), 0L) > now)) {
            combatTarget = null;
            stopCombatNav();
        }
        if (combatTarget == null && now >= nextCombatScan) {
            nextCombatScan = now + COMBAT_SCAN_INTERVAL;
            combatTarget = nearestHostile(now);
        }
        if (combatTarget == null) {
            return false;
        }
        if (player.distanceToSqr(combatTarget) <= ATTACK_REACH_SQR) {
            stopCombatNav();
            combatFails = 0;
            // 每次新建:瞄准 + 原生攻击冷却就绪才挥(软等待),冷却在玩家身上,无状态安全。
            Interaction.attackEntity(player, combatTarget).tick();
            return true;
        }
        if (combatNav == null) {
            combatNav = new PlayerNav(player, combatTarget::blockPosition, COMBAT_SPEED,
                    () -> player.distanceToSqr(combatTarget) <= ATTACK_REACH_SQR);
        }
        switch (combatNav.tick()) {
            case RUNNING, ARRIVED -> { }
            case FAILED -> {
                stopCombatNav();
                // 追不上(骷髅站在够不着的悬崖上)几次就放弃它一阵子,免得空耗。
                if (++combatFails >= COMBAT_FAIL_LIMIT) {
                    combatUnreachable.put(combatTarget.getId(), now + COMBAT_IGNORE_TICKS);
                    combatTarget = null;
                    combatFails = 0;
                }
            }
        }
        return true;
    }

    /** 近处最近的敌对怪物(被忽略中的除外)。 */
    private LivingEntity nearestHostile(long now) {
        AABB box = player.getBoundingBox().inflate(COMBAT_SCAN_RADIUS);
        LivingEntity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Monster m : player.level().getEntitiesOfClass(Monster.class, box)) {
            if (combatUnreachable.getOrDefault(m.getId(), 0L) > now) {
                continue;
            }
            double d = player.distanceToSqr(m);
            if (d < bestSqr) {
                bestSqr = d;
                best = m;
            }
        }
        return best;
    }

    private void stopCombatNav() {
        if (combatNav != null) {
            combatNav.stop();
            combatNav = null;
        }
    }

    /**
     * 社交一帧。返回 true = 本帧被社交占用(动作执行中或刚触发)。
     * 动作做完了返回 false,闲逛/跟随继续——<b>不打断任务</b>。
     */
    private boolean tickSocial(Entity target, long now) {
        if (social == null) {
            ServerPlayer owner = companionOwner();
            boolean near = owner != null
                    && player.distanceToSqr(owner) <= SOCIAL_RANGE * SOCIAL_RANGE;
            if (near && SocialSignals.hasFresh(player.getUUID(), now)) {
                var kind = SocialSignals.consume(player.getUUID(), now);
                if (kind != null) {
                    social = SocialReactions.pickReaction(kind, player);
                    socialTicksLeft = SocialReactions.duration(social);
                    SocialReactions.startMotion(player, social);
                }
            }
        }
        if (social == null) {
            return false;
        }
        // 扫视两段式:过半切到右侧。
        if (social == Motion.HEAD_SCAN && socialTicksLeft == SocialReactions.duration(Motion.HEAD_SCAN) / 2) {
            SocialReactions.scanSecondHalf(player);
        }
        if (--socialTicksLeft <= 0) {
            SocialReactions.finishMotion(player, social);
            social = null;
            restUntilGameTime = now + REST_TICKS;   // 社交完歇一会再继续逛
        }
        return true;
    }

    /** 闲逛点:目标周围 3~8 格随机方向。 */
    private NavGoal pickLoiterGoal(Entity target, long now) {
        BlockPos at = target.blockPosition();
        for (int i = 0; i < 8; i++) {
            double r = LOITER_MIN + Math.random() * (LOITER_MAX - LOITER_MIN);
            double a = Math.random() * Math.PI * 2;
            int x = at.getX() + (int) Math.round(Math.cos(a) * r);
            int z = at.getZ() + (int) Math.round(Math.sin(a) * r);
            BlockPos p = new BlockPos(x, at.getY(), z);
            if (player.level().isLoaded(p)) {
                return NavGoal.column(x, z);
            }
        }
        return null;
    }

    /** 闲逛时偶尔看向目标。 */
    private void maybeGaze(Entity target, long now) {
        if (now >= nextGazeGameTime && target instanceof ServerPlayer owner) {
            SocialMotion.lookAtOwner(player, owner);
            nextGazeGameTime = now + GAZE_EVERY_TICKS;
        }
    }

    private ServerPlayer companionOwner() {
        ServerPlayer owner = player.resolveOwnerPlayer();
        return owner == null || owner.level() != player.level() ? null : owner;
    }

    /** 跟着谁(与 FollowCompanionTask 同一套目标解析)。 */
    private Entity target(NumenPlayer companion) {
        if (r.entityId == null) {
            var owner = companion.resolveOwnerPlayer();
            return owner == null || owner.level() != companion.level() ? null : owner;
        }
        Entity e = ((ServerLevel) companion.level()).getEntity(r.entityId);
        if (e == null || e.isRemoved() || e == companion) {
            return null;
        }
        return r.targetUuid != null && !r.targetUuid.equals(e.getUUID()) ? null : e;
    }

    @Override
    protected String successMessage() {
        return "陪着主人";
    }

    @Override
    protected String cancelledMessage() {
        String who = r.entityId == null ? "主人" : "实体 " + r.entityId;
        return "不再陪在" + who + "身边了";
    }
}
