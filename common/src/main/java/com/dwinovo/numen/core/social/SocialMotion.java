package com.dwinovo.numen.core.social;

import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 微动作原语——SoulCraft 式"活人感"的肢体层。MC 玩家实体没有挥手/歪头的
 * 动画,全用服务端可驱动的原语组合:挥臂({@code swing})、蹲下
 * ({@link InputDriver#sneak})、转头({@link InputDriver#lookAt})。
 *
 * <p>每个动作都是一次性触发,时长由调用方(SocialChain)按 tick 计数控制——
 * 蹲下要维持几刻,链自己负责收尾({@link #crouch(p, false)})。
 *
 * <p><b>平滑转头</b>:视线类动作不再瞬移 {@code lookAt},而是记录目标点
 * ({@link #startLook}),由调用方每 tick 驱动 {@link #tickLook}——头朝向
 * 以每 tick 最大 {@value #MAX_TURN_DEG}° 的速度转过去,只转头({@code
 * setYHeadRot}/{@code setXRot}),身体不动,转完自然停住。
 */
public final class SocialMotion {

    private SocialMotion() {}

    /** 每 tick 最大转头角度(度)——14°/tick ≈ 0.7 秒转 90°,不快不慢。 */
    private static final double MAX_TURN_DEG = 14.0;
    /** 到位判定阈值(度):差这么多以内直接锁死目标。 */
    private static final double LOCK_DEG = 3.0;

    /** 平滑转头目标:companion UUID → 目标点。 */
    private static final Map<UUID, Vec3> LOOK_TARGETS = new HashMap<>();

    // ------------------------------------------------------------------
    // 平滑转头
    // ------------------------------------------------------------------

    /** 记录转头目标(不瞬移)。 */
    public static void startLook(UUID companionUuid, Vec3 target) {
        LOOK_TARGETS.put(companionUuid, target);
    }

    /** 清除转头目标(动作结束/被打断)。 */
    public static void clearLook(UUID companionUuid) {
        LOOK_TARGETS.remove(companionUuid);
    }

    public static boolean hasLook(UUID companionUuid) {
        return LOOK_TARGETS.containsKey(companionUuid);
    }

    /**
     * 每 tick 驱动一次:把头的朝向往目标点插值。
     * 只动 {@code yHeadRot}/{@code xRot}(头),不动 {@code yRot}(身体)。
     */
    public static void tickLook(NumenPlayer self) {
        Vec3 target = LOOK_TARGETS.get(self.getUUID());
        if (target == null) {
            return;
        }
        Vec3 dir = target.subtract(self.getEyePosition()).normalize();
        double targetYaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double targetPitch = Math.toDegrees(Math.asin(-dir.y));

        float curYaw = self.getYHeadRot();
        float curPitch = self.getXRot();
        double dYaw = wrapDegrees(targetYaw - curYaw);
        double dPitch = targetPitch - curPitch;

        if (Math.abs(dYaw) <= LOCK_DEG && Math.abs(dPitch) <= LOCK_DEG) {
            // 到位:精确锁一次,然后保持(目标点固定,主人动了也不追——自然)。
            self.setYHeadRot((float) targetYaw);
            self.setXRot((float) targetPitch);
            LOOK_TARGETS.remove(self.getUUID());
            return;
        }
        double stepYaw = Math.copySign(Math.min(MAX_TURN_DEG, Math.abs(dYaw)), dYaw);
        double stepPitch = Math.copySign(Math.min(MAX_TURN_DEG, Math.abs(dPitch)), dPitch);
        self.setYHeadRot((float) (curYaw + stepYaw));
        self.setXRot((float) (curPitch + stepPitch));
    }

    /** 把角度规整到 [-180, 180)。 */
    private static double wrapDegrees(double d) {
        d = d % 360.0;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    // ------------------------------------------------------------------
    // 动作原语
    // ------------------------------------------------------------------

    /** 挥手:面向主人 + 挥一下主手(约 6 刻的挥臂动画)。 */
    public static void wave(NumenPlayer self, ServerPlayer owner) {
        startLook(self.getUUID(), owner.getEyePosition());
        self.swing(InteractionHand.MAIN_HAND);
    }

    /** 蹲下/站起:蹲的姿态由 MC 渲染,维持时长交给调用方。 */
    public static void crouch(NumenPlayer self, boolean on) {
        InputDriver.sneak(self, on);
    }

    /** 歪头:看向主人眼睛上方一点——MC 没有歪头动画,用视线偏移模拟。 */
    public static void headTilt(NumenPlayer self, ServerPlayer owner) {
        startLook(self.getUUID(), owner.getEyePosition().add(0, 0.35, 0));
    }

    /** 看向主人:视线跟到眼睛。 */
    public static void lookAtOwner(NumenPlayer self, ServerPlayer owner) {
        startLook(self.getUUID(), owner.getEyePosition());
    }

    /** 看向别处:视线转到水平方向偏左 45°。 */
    public static void lookAway(NumenPlayer self) {
        Vec3 look = self.getLookAngle();
        double yaw = Math.atan2(-look.x, look.z) - Math.PI / 4;
        Vec3 target = self.position().add(Math.sin(yaw) * 4, self.getEyeHeight(), Math.cos(yaw) * 4);
        startLook(self.getUUID(), target);
    }

    /** 跳一下。 */
    public static void jump(NumenPlayer self) {
        InputDriver.jump(self);
    }

    /** 朝主人凑近一步。 */
    public static void stepCloser(NumenPlayer self, ServerPlayer owner) {
        InputDriver.lookAt(self, owner.getEyePosition());
        InputDriver.stepToward(self, owner.position(), false);
    }

    /** 看向主人手里的东西(主手位置)。 */
    public static void lookAtHand(NumenPlayer self, ServerPlayer owner) {
        startLook(self.getUUID(), owner.getMainHandItem() == null
                ? owner.getEyePosition()
                : owner.position().add(0, 1.2, 0));
    }

    /** 扫视:先看主人左侧,再看右侧(两段式,由调用方分两次触发或长一点)。
     *  这里是第一段——向左 40°,持续一段后 SocialChain 换 {@link #headScanRight}。 */
    public static void headScanLeft(NumenPlayer self, ServerPlayer owner) {
        Vec3 eye = owner.getEyePosition();
        Vec3 dir = eye.subtract(self.getEyePosition()).normalize();
        double yaw = Math.atan2(-dir.x, dir.z) + Math.PI / 4;
        Vec3 target = self.getEyePosition().add(Math.sin(yaw) * 4, 0, Math.cos(yaw) * 4);
        startLook(self.getUUID(), target);
    }

    /** 扫视第二段:向右 40°。 */
    public static void headScanRight(NumenPlayer self, ServerPlayer owner) {
        Vec3 eye = owner.getEyePosition();
        Vec3 dir = eye.subtract(self.getEyePosition()).normalize();
        double yaw = Math.atan2(-dir.x, dir.z) - Math.PI / 4;
        Vec3 target = self.getEyePosition().add(Math.sin(yaw) * 4, 0, Math.cos(yaw) * 4);
        startLook(self.getUUID(), target);
    }

    /** 反击作势:面向主人挥剑——只作势,不真打(信任主人的分寸)。 */
    public static void counterSwing(NumenPlayer self, ServerPlayer owner) {
        startLook(self.getUUID(), owner.getEyePosition());
        self.swing(InteractionHand.MAIN_HAND);
    }

    /** 点头:快速蹲一下再站起——由调用方控制时长,这里是蹲下。 */
    public static void nod(NumenPlayer self, boolean on) {
        InputDriver.sneak(self, on);
    }

    /** 跟随主人的视线:看向主人目光方向 10 格外的点。 */
    public static void followGaze(NumenPlayer self, ServerPlayer owner) {
        Vec3 eye = owner.getEyePosition();
        Vec3 look = owner.getLookAngle();
        startLook(self.getUUID(), eye.add(look.x * 10, look.y * 10, look.z * 10));
    }

    /** 绕主人侧移一步(左右交替由调用方决定):看向主人 + 朝侧面走半步。 */
    public static void sidestep(NumenPlayer self, ServerPlayer owner, boolean left) {
        InputDriver.lookAt(self, owner.getEyePosition());
        Vec3 toOwner = owner.position().subtract(self.position()).normalize();
        double yaw = Math.atan2(-toOwner.x, toOwner.z) + (left ? -Math.PI / 2 : Math.PI / 2);
        Vec3 target = self.position().add(Math.sin(yaw) * 2, 0, Math.cos(yaw) * 2);
        InputDriver.stepToward(self, target, false);
    }
}
