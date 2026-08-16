package com.dwinovo.numen.core.social;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家动作信号检测——SoulCraft 式"玩家动作 = 观察,不是命令"的入口。
 *
 * <p>两类来源:
 * <ul>
 *   <li><b>采样式</b>(每 tick 由 {@link #detectTick} 驱动):主人的挥臂(swing 边沿)、
 *       蹲下(crouch 边沿)、视线对上(看向同伴)。</li>
 *   <li><b>事件式</b>(NeoForge 总线,mod 侧转发):右键互动(=送礼物/搭话)、攻击同伴。</li>
 * </ul>
 *
 * <p>信号按同伴 UUID 存最近的 {@code 1} 条,SocialChain 消费后即清除——
 * 动作只做一次,不做成持续循环。全部带游戏刻时间戳,过期(超过 40 刻)自动失效。
 */
public final class SocialSignals {

    /** 信号种类——SocialChain 的概率反应表按它分发(SoulCraft 14 种信号)。 */
    public enum Kind {
        WAVE("wave"), CROUCH("crouch"), GIFT("gift"), DROP_ITEM("drop_item"),
        ATTACK("attack"), LOOK_AT_ME("look_at_me"), JUMP("jump"),
        MINING("mining"), PLACING("placing"), INTERACTING("interacting"),
        LOOK_CHANGE("look_change"), ATTACK_OTHER("attack_other"),
        COLLECT_ITEM("collect_item"), GREETING("greeting");

        final String wire;
        Kind(String wire) { this.wire = wire; }
    }

    public record Signal(Kind kind, long gameTime) {}

    /** 信号在此刻之前多久内有效(刻)。 */
    private static final int FRESH_TICKS = 40;

    /** 挥臂检测的状态(每同伴):上一刻有没有在挥。 */
    private static final Map<UUID, Boolean> PREV_SWINGING = new HashMap<>();
    /** 挥臂开始时刻(刻)——结束时长 ≤8 刻算挥手,>8 刻算挖矿。 */
    private static final Map<UUID, Long> SWING_START = new HashMap<>();
    /** 蹲下边沿检测的状态。 */
    private static final Map<UUID, Boolean> PREV_CROUCHING = new HashMap<>();
    /** 跳跃边沿检测:上一刻在不在空中。 */
    private static final Map<UUID, Boolean> PREV_AIRBORNE = new HashMap<>();
    /** 视线转向检测:上一刻的 lookAngle(检测转头 >30°)。 */
    private static final Map<UUID, net.minecraft.world.phys.Vec3> PREV_LOOK = new HashMap<>();
    private static final int LOOK_CHANGE_COOLDOWN_TICKS = 30;
    private static final Map<UUID, Long> LAST_LOOK_CHANGE = new HashMap<>();
    /** 挥臂时长分界:≤ 此值 = 挥手,> 此值 = 挖矿(刻)。 */
    private static final int SWING_WAVE_MAX_TICKS = 8;
    /** 注视检测:两次上报之间的最小间隔(刻),免得被盯住时每刻刷一次。 */
    private static final Map<UUID, Long> LAST_GAZE = new HashMap<>();
    private static final int GAZE_COOLDOWN_TICKS = 60;
    /** 迎客检测:主人从远处进入这个范围算一次"新接触"(边沿),同侧重复进出不刷屏。 */
    private static final double GREETING_ENTER_RADIUS = 16.0;
    private static final double GREETING_EXIT_RADIUS = 20.0;
    /** 迎客冷却(刻):主人出去又回来,至少隔这么久才再打招呼。 */
    private static final int GREETING_COOLDOWN_TICKS = 1200;
    private static final Map<UUID, Long> LAST_GREETING = new HashMap<>();
    /** 主人当前在不在迎客圈内(边沿状态)。 */
    private static final Map<UUID, Boolean> OWNER_INSIDE = new HashMap<>();

    /** 最近的信号(每同伴一条)。 */
    private static final Map<UUID, Signal> LATEST = new HashMap<>();

    private SocialSignals() {}

    /** 事件式入口(NeoForge 转发)。 */
    public static void record(UUID companion, Kind kind, long gameTime) {
        LATEST.put(companion, new Signal(kind, gameTime));
    }

    /** 看一眼有没有新鲜信号(不消费)。 */
    public static boolean hasFresh(UUID companion, long now) {
        Signal s = LATEST.get(companion);
        return s != null && now - s.gameTime() <= FRESH_TICKS;
    }

    /** 取走并清除最近的信号;不新鲜或没有则 null。 */
    public static Kind consume(UUID companion, long now) {
        Signal s = LATEST.remove(companion);
        return s != null && now - s.gameTime() <= FRESH_TICKS ? s.kind() : null;
    }

    /** 反应冷却:两次触发反应之间的最短间隔(刻),区间内随机。 */
    private static final int REACTION_COOLDOWN_MIN = 40;
    private static final int REACTION_COOLDOWN_MAX = 80;
    /** 下次允许反应的时刻(刻)。 */
    private static final Map<UUID, Long> NEXT_REACTION_AT = new HashMap<>();

    /**
     * 取信号并应用反应冷却——两个社交调用方(SocialChain / 陪伴任务)统一走这里,
     * 防止主人连续互动时女仆像复读机一样不停反应。
     *
     * <p>冷却期内信号照常消费(错过就是错过,活人也不会对每一下都有反应),
     * 但返回 null 不触发动作。冷却随机 40~80 刻(2~4 秒),避免机械节拍。
     */
    public static Kind consumeReaction(UUID companion, long now) {
        Kind kind = consume(companion, now);
        if (kind == null) {
            return null;
        }
        long next = NEXT_REACTION_AT.getOrDefault(companion, 0L);
        if (now < next) {
            return null;
        }
        long cooldown = REACTION_COOLDOWN_MIN
                + (long) (Math.random() * (REACTION_COOLDOWN_MAX - REACTION_COOLDOWN_MIN));
        NEXT_REACTION_AT.put(companion, now + cooldown);
        return kind;
    }

    /**
     * 每 tick 采样所有在线的同伴:主人姿态边沿 → 信号。
     *
     * <p>挥臂边沿:从"没在挥"到"在挥"算一次 WAVE,且单次挥臂限报一次(冷却)。
     * 蹲下边沿同理。视线:主人视线锥(±30°)罩住同伴头顶算对上,按冷却上报。
     */
    public static void detectTick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof NumenPlayer companion)) {
                continue;
            }
            ServerPlayer owner = companion.resolveOwnerPlayer();
            if (owner == null || owner.level() != companion.level()) {
                continue;
            }
            UUID cid = companion.getUUID();
            UUID oid = owner.getUUID();

            // 挥臂:开始时刻记下,结束按时长分挥手/挖矿(短挥 = 打招呼,
            // 长按 = 挖东西)。
            boolean swinging = owner.swinging;
            boolean was = PREV_SWINGING.getOrDefault(cid, false);
            PREV_SWINGING.put(cid, swinging);
            if (swinging && !was) {
                SWING_START.put(cid, now);
            } else if (!swinging && was) {
                long start = SWING_START.getOrDefault(cid, now);
                record(cid, now - start <= SWING_WAVE_MAX_TICKS ? Kind.WAVE : Kind.MINING, now);
                SWING_START.remove(cid);
            }

            // 蹲下边沿。
            boolean crouching = owner.isCrouching();
            boolean wasC = PREV_CROUCHING.getOrDefault(cid, false);
            PREV_CROUCHING.put(cid, crouching);
            if (crouching && !wasC) {
                record(cid, Kind.CROUCH, now);
            }

            // 跳跃边沿:从落地到离地。
            boolean airborne = !owner.onGround();
            boolean wasA = PREV_AIRBORNE.getOrDefault(cid, false);
            PREV_AIRBORNE.put(cid, airborne);
            if (airborne && !wasA) {
                record(cid, Kind.JUMP, now);
            }

            // 视线转向:转头超过 30° 算一次"看向别处"(冷却 30 刻)。
            net.minecraft.world.phys.Vec3 look = owner.getLookAngle();
            net.minecraft.world.phys.Vec3 prev = PREV_LOOK.get(cid);
            if (prev != null) {
                long lastLc = LAST_LOOK_CHANGE.getOrDefault(cid, 0L);
                if (now - lastLc >= LOOK_CHANGE_COOLDOWN_TICKS
                        && prev.dot(look) < Math.cos(Math.toRadians(30))) {
                    LAST_LOOK_CHANGE.put(cid, now);
                    record(cid, Kind.LOOK_CHANGE, now);
                }
            }
            PREV_LOOK.put(cid, look);

            // 视线对上(冷却 60 刻)。初值用 0 而不是 Long.MIN_VALUE:
            // now - MIN_VALUE 会溢出成负数,导致第一次永远不触发。
            long last = LAST_GAZE.getOrDefault(cid, 0L);
            if (now - last >= GAZE_COOLDOWN_TICKS && lookingAt(owner, companion)) {
                LAST_GAZE.put(cid, now);
                record(cid, Kind.LOOK_AT_ME, now);
            }

            // 迎客:主人从圈外进入圈内(边沿),且距上次打招呼够久。
            double dist = owner.distanceTo(companion);
            boolean inside = dist <= GREETING_ENTER_RADIUS;
            boolean wasInside = OWNER_INSIDE.getOrDefault(cid, false);
            OWNER_INSIDE.put(cid, inside);
            long lastGreeting = LAST_GREETING.getOrDefault(cid, 0L);
            if (inside && !wasInside && now - lastGreeting >= GREETING_COOLDOWN_TICKS) {
                LAST_GREETING.put(cid, now);
                record(cid, Kind.GREETING, now);
            } else if (!inside && dist > GREETING_EXIT_RADIUS) {
                // 完全走出去才允许下次再算"新接触"(迟滞,避免在边界上反复横跳)。
                OWNER_INSIDE.put(cid, false);
            }
        }
    }

    /** 主人的视线锥是否罩住同伴的头顶(±30°,水平距离 10 格内)。 */
    private static boolean lookingAt(ServerPlayer owner, NumenPlayer companion) {
        Vec3 eye = owner.getEyePosition();
        Vec3 target = companion.position().add(0, 1.6, 0);   // 同伴头顶
        double dist = eye.distanceTo(target);
        if (dist > 10) {
            return false;
        }
        Vec3 dir = target.subtract(eye).normalize();
        Vec3 look = owner.getLookAngle();
        return look.dot(dir) >= Math.cos(Math.toRadians(30));
    }
}
