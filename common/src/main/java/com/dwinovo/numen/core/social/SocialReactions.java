package com.dwinovo.numen.core.social;

import com.dwinovo.numen.core.social.SocialSignals.Kind;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 概率反应表 + 动作执行——SoulCraft {@code choosePlayerReaction} 的移植,
 * 供 {@code SocialChain}(空闲时的社交)与 {@code CompanyCompanionTask}(陪伴
 * 跟随中的社交)共用。
 *
 * <p>决策是纯函数 {@link #pickReaction}:输入信号 + 自身状态,输出一个动作;
 * 同池等概率随机。概率是"不确定性",不是"随机乱来"——条件(血量低先防御)
 * 优先于随机。
 */
public final class SocialReactions {

    /** 所有可执行的动作原语。 */
    public enum Motion {
        WAVE, CROUCH, HEAD_TILT, LOOK_OWNER, JUMP, STEP_CLOSER, LOOK_HAND,
        LOOK_AWAY, HEAD_SCAN, COUNTER, NOD, IGNORE, SIDE_STEP_L, SIDE_STEP_R,
        FOLLOW_GAZE
    }

    private SocialReactions() {}

    /**
     * 概率反应表(纯函数)——等概率随机池;条件分支(血量低先防御)优先于随机。
     */
    public static Motion pickReaction(Kind kind, NumenPlayer companion) {
        double health = companion.getHealth();
        return switch (kind) {
            case ATTACK -> {
                if (health <= 10) {
                    yield Motion.CROUCH;   // 血低:蹲下防御(不逃远,家就在旁边)
                }
                yield pick(Motion.COUNTER, Motion.CROUCH, Motion.SIDE_STEP_L,
                        Motion.LOOK_OWNER, Motion.HEAD_TILT);
            }
            case GIFT -> pick(Motion.LOOK_HAND, Motion.CROUCH, Motion.NOD,
                    Motion.STEP_CLOSER, Motion.LOOK_HAND);
            case DROP_ITEM -> pick(Motion.LOOK_HAND, Motion.CROUCH, Motion.NOD,
                    Motion.LOOK_HAND, Motion.STEP_CLOSER);
            case CROUCH -> pick(Motion.CROUCH, Motion.HEAD_TILT, Motion.STEP_CLOSER,
                    Motion.HEAD_SCAN, Motion.WAVE);
            case JUMP -> pick(Motion.JUMP, Motion.WAVE, Motion.LOOK_OWNER);
            case LOOK_AT_ME -> pick(Motion.LOOK_OWNER, Motion.HEAD_TILT, Motion.LOOK_AWAY);
            case WAVE -> pick(Motion.WAVE, Motion.LOOK_OWNER, Motion.HEAD_TILT);
            case MINING -> pick(Motion.LOOK_OWNER, Motion.CROUCH, Motion.WAVE, Motion.HEAD_TILT);
            case PLACING -> pick(Motion.LOOK_OWNER, Motion.NOD, Motion.WAVE);
            case INTERACTING -> pick(Motion.LOOK_OWNER, Motion.NOD, Motion.WAVE);
            case LOOK_CHANGE -> pick(Motion.FOLLOW_GAZE, Motion.LOOK_OWNER, Motion.IGNORE);
            case ATTACK_OTHER -> pick(Motion.LOOK_OWNER, Motion.HEAD_TILT, Motion.NOD,
                    Motion.STEP_CLOSER);
            case COLLECT_ITEM -> pick(Motion.LOOK_OWNER, Motion.NOD, Motion.IGNORE);
            case GREETING -> Motion.LOOK_OWNER;
        };
    }

    /** 等概率随机挑一个。 */
    private static Motion pick(Motion... options) {
        return options[(int) (Math.random() * options.length)];
    }

    public static int duration(Motion m) {
        return switch (m) {
            case WAVE -> 8;
            case CROUCH -> 14;
            case HEAD_TILT -> 6;
            case LOOK_OWNER -> 6;
            case JUMP -> 4;
            case STEP_CLOSER -> 10;
            case LOOK_HAND -> 6;
            case LOOK_AWAY -> 6;
            case HEAD_SCAN -> 14;
            case COUNTER -> 8;
            case NOD -> 8;
            case IGNORE -> 2;
            case SIDE_STEP_L, SIDE_STEP_R -> 10;
            case FOLLOW_GAZE -> 6;
        };
    }

    /** 触发动作(一次性)。 */
    public static void startMotion(NumenPlayer companion, Motion m) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) {
            return;
        }
        switch (m) {
            case WAVE -> SocialMotion.wave(companion, owner);
            case CROUCH -> SocialMotion.crouch(companion, true);
            case HEAD_TILT -> SocialMotion.headTilt(companion, owner);
            case LOOK_OWNER -> {
                InputDriver.halt(companion);
                SocialMotion.lookAtOwner(companion, owner);
            }
            case JUMP -> SocialMotion.jump(companion);
            case STEP_CLOSER -> SocialMotion.stepCloser(companion, owner);
            case LOOK_HAND -> {
                InputDriver.halt(companion);
                SocialMotion.lookAtHand(companion, owner);
            }
            case LOOK_AWAY -> {
                InputDriver.halt(companion);
                SocialMotion.lookAway(companion);
            }
            case HEAD_SCAN -> SocialMotion.headScanLeft(companion, owner);
            case COUNTER -> SocialMotion.counterSwing(companion, owner);
            case NOD -> SocialMotion.nod(companion, true);
            case IGNORE -> { }
            case SIDE_STEP_L -> SocialMotion.sidestep(companion, owner, true);
            case SIDE_STEP_R -> SocialMotion.sidestep(companion, owner, false);
            case FOLLOW_GAZE -> SocialMotion.followGaze(companion, owner);
        }
    }

    /** 扫视两段式的后半段(动作过半时切到右侧)。 */
    public static void scanSecondHalf(NumenPlayer companion) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner != null) {
            SocialMotion.headScanRight(companion, owner);
        }
    }

    /** 收尾(蹲姿复位等)。 */
    public static void finishMotion(NumenPlayer companion, Motion m) {
        if (m == Motion.CROUCH || m == Motion.NOD) {
            SocialMotion.crouch(companion, false);
            SocialMotion.nod(companion, false);
        }
    }
}
