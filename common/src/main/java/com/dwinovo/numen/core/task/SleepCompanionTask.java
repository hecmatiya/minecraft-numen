package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.GoToThenDoTask;
import com.dwinovo.numen.core.tools.SleepOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import com.mojang.datafixers.util.Either;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code sleep} 的"自动走到床边再睡"执行器——回家时不必精确站在床边,
 * 调一次 sleep,身体自己导航到床旁(原版睡眠距离判据:床中心 3 格内),
 * 然后躺下并确认真的睡着了。
 *
 * <p>睡眠判定与文案复用 {@link SleepOps}:被拒时直接给原版自己的理由
 * (白天/有怪/床被占),成功时确认 {@code isSleeping()} 而非"请求被接受"。
 */
public final class SleepCompanionTask extends GoToThenDoTask<SleepTaskRecord> {

    /** 原版 startSleepInBed 的距离判据:床中心 3 格内。 */
    private static final double SLEEP_REACH_SQR = 3.0 * 3.0;
    private static final double WALK_SPEED = 1.0;

    private boolean slept;

    public SleepCompanionTask(NumenPlayer player, SleepTaskRecord record) {
        super(player, record);
    }

    @Override
    protected PlayerNav buildNav() {
        // 目标 = 床头;到达判定 = 床中心 3 格内(原版睡眠判据)。
        return new PlayerNav(player, () -> r.bedHead, WALK_SPEED, this::inSleepReach);
    }

    @Override
    protected boolean reached() {
        return inSleepReach();
    }

    private boolean inSleepReach() {
        Vec3 center = Vec3.atCenterOf(r.bedHead);
        return player.distanceToSqr(center) <= SLEEP_REACH_SQR;
    }

    @Override
    protected TaskState act() {
        if (slept) {
            return TaskState.SUCCESS;
        }
        Either<Player.BedSleepingProblem, Unit> result = player.startSleepInBed(r.bedHead);
        Player.BedSleepingProblem rejection = result.left().orElse(null);
        if (rejection != null) {
            fail(SleepOps.explain(rejection) + " (bed at " + pretty(r.bedHead) + ")",
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        if (!player.isSleeping()) {
            fail("the sleep request was accepted but you did not actually enter sleep — "
                    + "something cancelled it in the same tick; look around before assuming you rested",
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        slept = true;
        return TaskState.SUCCESS;
    }

    private static String pretty(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> out = new HashMap<>();
        out.put("bed", Map.of("x", r.bedHead.getX(), "y", r.bedHead.getY(), "z", r.bedHead.getZ()));
        out.put("sleeping", slept);
        return out;
    }

    @Override
    protected String successMessage() {
        return "walked to the bed at " + pretty(r.bedHead)
                + " and the server confirms you are sleeping. Night passes on its own.";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before reaching the bed at " + pretty(r.bedHead);
    }

    @Override
    protected String cancelledMessage() {
        return "sleep interrupted";
    }
}
