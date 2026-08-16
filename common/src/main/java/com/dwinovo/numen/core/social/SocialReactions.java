package com.dwinovo.numen.core.social;

import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 概率反应表 + 动作执行——SoulCraft {@code choosePlayerReaction} 的移植,
 * 供 {@code SocialChain}(空闲时的社交)与 {@code CompanyCompanionTask}(陪伴
 * 跟随中的社交)共用。
 *
 * <p>决策是纯函数 {@link #pickReaction}:输入信号 + 自身状态,输出一个动作。
 * <b>池子里 Motion 重复出现 = 权重</b>(重复越多越容易被选中)——概率是
 * "不确定性",不是"随机乱来":条件(血量低先防御)优先于随机,性格差异
 * (傲娇/温柔/均衡)优先于全局随机。
 *
 * <p>性格档案:默认 {@link Profile#DEFAULT};可在 {@code config/numen/social.json}
 * 里按同伴 UUID 指定:
 * <pre>{ "858e449c-4d13-48c5-9c94-af9a0ba68e9d": "tsun", "……": "gentle" }</pre>
 */
public final class SocialReactions {

    /** 所有可执行的动作原语。 */
    public enum Motion {
        WAVE, CROUCH, HEAD_TILT, LOOK_OWNER, JUMP, STEP_CLOSER, LOOK_HAND,
        LOOK_AWAY, HEAD_SCAN, COUNTER, NOD, IGNORE, SIDE_STEP_L, SIDE_STEP_R,
        FOLLOW_GAZE
    }

    /** 性格档案——决定同一信号下每个动作的权重倾向。 */
    public enum Profile {
        DEFAULT, TSUN, GENTLE;

        private final Map<SocialSignals.Kind, Motion[]> pools = new EnumMap<>(SocialSignals.Kind.class);
        private boolean built;

        void build() {
            if (built) return;
            built = true;
            switch (this) {
                case DEFAULT -> buildDefault(pools);
                case TSUN -> buildTsun(pools);
                case GENTLE -> buildGentle(pools);
            }
        }
    }

    /** 每同伴的性格(没配置 = DEFAULT)。 */
    private static final Map<UUID, Profile> PROFILES = new HashMap<>();

    private SocialReactions() {}

    // ------------------------------------------------------------------
    // 性格反应池(池内 Motion 重复 = 加权)
    // ------------------------------------------------------------------

    private static void buildDefault(Map<SocialSignals.Kind, Motion[]> pools) {
        pools.put(SocialSignals.Kind.ATTACK, new Motion[]{Motion.COUNTER, Motion.CROUCH,
                Motion.SIDE_STEP_L, Motion.SIDE_STEP_R, Motion.LOOK_OWNER, Motion.HEAD_TILT,
                Motion.LOOK_AWAY});
        pools.put(SocialSignals.Kind.GIFT, new Motion[]{Motion.LOOK_HAND, Motion.LOOK_HAND,
                Motion.NOD, Motion.STEP_CLOSER, Motion.CROUCH, Motion.LOOK_AWAY});
        pools.put(SocialSignals.Kind.DROP_ITEM, new Motion[]{Motion.LOOK_HAND, Motion.LOOK_HAND,
                Motion.NOD, Motion.STEP_CLOSER, Motion.CROUCH});
        pools.put(SocialSignals.Kind.CROUCH, new Motion[]{Motion.CROUCH, Motion.HEAD_TILT,
                Motion.STEP_CLOSER, Motion.HEAD_SCAN, Motion.NOD});
        pools.put(SocialSignals.Kind.JUMP, new Motion[]{Motion.JUMP, Motion.WAVE,
                Motion.LOOK_OWNER, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.LOOK_AT_ME, new Motion[]{Motion.LOOK_OWNER,
                Motion.HEAD_TILT, Motion.LOOK_AWAY, Motion.NOD});
        pools.put(SocialSignals.Kind.WAVE, new Motion[]{Motion.WAVE, Motion.WAVE,
                Motion.LOOK_OWNER, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.MINING, new Motion[]{Motion.LOOK_OWNER, Motion.HEAD_TILT,
                Motion.NOD, Motion.STEP_CLOSER, Motion.IGNORE});
        pools.put(SocialSignals.Kind.PLACING, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.HEAD_TILT, Motion.STEP_CLOSER});
        pools.put(SocialSignals.Kind.INTERACTING, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.HEAD_TILT, Motion.STEP_CLOSER});
        pools.put(SocialSignals.Kind.LOOK_CHANGE, new Motion[]{Motion.FOLLOW_GAZE,
                Motion.LOOK_OWNER, Motion.IGNORE, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.ATTACK_OTHER, new Motion[]{Motion.LOOK_OWNER,
                Motion.HEAD_TILT, Motion.NOD, Motion.STEP_CLOSER, Motion.WAVE});
        pools.put(SocialSignals.Kind.COLLECT_ITEM, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.IGNORE, Motion.LOOK_HAND});
        pools.put(SocialSignals.Kind.GREETING, new Motion[]{Motion.LOOK_OWNER, Motion.WAVE,
                Motion.STEP_CLOSER, Motion.HEAD_SCAN});
    }

    private static void buildTsun(Map<SocialSignals.Kind, Motion[]> pools) {
        // 傲娇:嘴硬心软——爱答不理权重高,但"收礼/回家"时藏不住的在意。
        pools.put(SocialSignals.Kind.ATTACK, new Motion[]{Motion.COUNTER, Motion.COUNTER,
                Motion.SIDE_STEP_L, Motion.SIDE_STEP_R, Motion.LOOK_AWAY, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.GIFT, new Motion[]{Motion.LOOK_HAND, Motion.LOOK_AWAY,
                Motion.LOOK_AWAY, Motion.NOD, Motion.STEP_CLOSER, Motion.CROUCH});
        pools.put(SocialSignals.Kind.DROP_ITEM, new Motion[]{Motion.LOOK_AWAY, Motion.LOOK_HAND,
                Motion.CROUCH, Motion.NOD, Motion.LOOK_AWAY});
        pools.put(SocialSignals.Kind.CROUCH, new Motion[]{Motion.HEAD_TILT, Motion.STEP_CLOSER,
                Motion.HEAD_SCAN, Motion.CROUCH, Motion.NOD});
        pools.put(SocialSignals.Kind.JUMP, new Motion[]{Motion.LOOK_OWNER, Motion.HEAD_TILT,
                Motion.JUMP, Motion.LOOK_AWAY});
        pools.put(SocialSignals.Kind.LOOK_AT_ME, new Motion[]{Motion.LOOK_AWAY, Motion.LOOK_AWAY,
                Motion.HEAD_TILT, Motion.LOOK_OWNER});
        pools.put(SocialSignals.Kind.WAVE, new Motion[]{Motion.HEAD_TILT, Motion.LOOK_OWNER,
                Motion.WAVE, Motion.LOOK_AWAY});
        pools.put(SocialSignals.Kind.MINING, new Motion[]{Motion.LOOK_OWNER, Motion.HEAD_TILT,
                Motion.IGNORE, Motion.IGNORE});
        pools.put(SocialSignals.Kind.PLACING, new Motion[]{Motion.LOOK_OWNER, Motion.HEAD_TILT,
                Motion.NOD, Motion.IGNORE});
        pools.put(SocialSignals.Kind.INTERACTING, new Motion[]{Motion.LOOK_OWNER,
                Motion.HEAD_TILT, Motion.IGNORE});
        pools.put(SocialSignals.Kind.LOOK_CHANGE, new Motion[]{Motion.FOLLOW_GAZE,
                Motion.LOOK_AWAY, Motion.IGNORE, Motion.LOOK_OWNER});
        pools.put(SocialSignals.Kind.ATTACK_OTHER, new Motion[]{Motion.LOOK_OWNER,
                Motion.HEAD_TILT, Motion.NOD, Motion.WAVE, Motion.COUNTER});
        pools.put(SocialSignals.Kind.COLLECT_ITEM, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.IGNORE, Motion.IGNORE});
        pools.put(SocialSignals.Kind.GREETING, new Motion[]{Motion.LOOK_OWNER, Motion.WAVE,
                Motion.HEAD_TILT, Motion.STEP_CLOSER, Motion.LOOK_AWAY});
    }

    private static void buildGentle(Map<SocialSignals.Kind, Motion[]> pools) {
        // 温柔:热烈回应、专注看主人干活、被打不还手。
        pools.put(SocialSignals.Kind.ATTACK, new Motion[]{Motion.LOOK_OWNER, Motion.CROUCH,
                Motion.HEAD_TILT, Motion.NOD, Motion.SIDE_STEP_L});
        pools.put(SocialSignals.Kind.GIFT, new Motion[]{Motion.LOOK_HAND, Motion.LOOK_HAND,
                Motion.NOD, Motion.NOD, Motion.STEP_CLOSER, Motion.LOOK_OWNER});
        pools.put(SocialSignals.Kind.DROP_ITEM, new Motion[]{Motion.LOOK_HAND, Motion.LOOK_HAND,
                Motion.NOD, Motion.STEP_CLOSER, Motion.LOOK_OWNER});
        pools.put(SocialSignals.Kind.CROUCH, new Motion[]{Motion.NOD, Motion.HEAD_TILT,
                Motion.STEP_CLOSER, Motion.CROUCH, Motion.WAVE});
        pools.put(SocialSignals.Kind.JUMP, new Motion[]{Motion.JUMP, Motion.WAVE,
                Motion.LOOK_OWNER, Motion.NOD});
        pools.put(SocialSignals.Kind.LOOK_AT_ME, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.HEAD_TILT, Motion.WAVE});
        pools.put(SocialSignals.Kind.WAVE, new Motion[]{Motion.WAVE, Motion.WAVE,
                Motion.LOOK_OWNER, Motion.NOD, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.MINING, new Motion[]{Motion.LOOK_OWNER, Motion.LOOK_OWNER,
                Motion.NOD, Motion.STEP_CLOSER, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.PLACING, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.NOD, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.INTERACTING, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.LOOK_CHANGE, new Motion[]{Motion.FOLLOW_GAZE,
                Motion.FOLLOW_GAZE, Motion.LOOK_OWNER, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.ATTACK_OTHER, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.STEP_CLOSER, Motion.WAVE});
        pools.put(SocialSignals.Kind.COLLECT_ITEM, new Motion[]{Motion.LOOK_OWNER, Motion.NOD,
                Motion.NOD, Motion.HEAD_TILT});
        pools.put(SocialSignals.Kind.GREETING, new Motion[]{Motion.WAVE, Motion.WAVE,
                Motion.LOOK_OWNER, Motion.STEP_CLOSER, Motion.NOD});
    }

    // ------------------------------------------------------------------
    // 性格查询与配置
    // ------------------------------------------------------------------

    public static Profile profileOf(UUID companionUuid) {
        return PROFILES.getOrDefault(companionUuid, Profile.DEFAULT);
    }

    public static void setProfile(UUID companionUuid, Profile profile) {
        PROFILES.put(companionUuid, profile);
    }

    /**
     * 从 {@code config/numen/social.json} 加载性格映射:
     * <pre>{ "&lt;companion-uuid&gt;": "tsun" | "gentle" | "default" }</pre>
     * 文件不存在或解析失败 = 全部默认,不报错。
     */
    public static void loadConfig(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    Profile p = Profile.valueOf(e.getValue().getAsString().trim().toUpperCase());
                    setProfile(uuid, p);
                } catch (Exception ignore) {
                    // 单条坏配置跳过,不影响其它
                }
            }
        } catch (Exception ignore) {
            // 整个文件坏了 = 全部默认
        }
    }

    // ------------------------------------------------------------------
    // 决策
    // ------------------------------------------------------------------

    /**
     * 纯函数决策:条件(血量低先防御)优先,然后按性格的加权池随机。
     */
    public static Motion pickReaction(SocialSignals.Kind kind, NumenPlayer companion) {
        if (kind == SocialSignals.Kind.ATTACK && companion.getHealth() <= 10) {
            return Motion.CROUCH;   // 血低:蹲下防御(不逃远,家就在旁边)
        }
        Profile profile = profileOf(companion.getUUID());
        profile.build();
        Motion[] pool = profile.pools.get(kind);
        if (pool == null || pool.length == 0) {
            return Motion.LOOK_OWNER;   // 未知信号兜底
        }
        return pool[(int) (Math.random() * pool.length)];
    }

    // ------------------------------------------------------------------
    // 时长与执行
    // ------------------------------------------------------------------

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

    /** 触发动作(一次性)。视线类动作走平滑转头(由调用方每 tick 驱动)。 */
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

    /** 收尾(蹲姿复位、平滑转头目标清除等)。 */
    public static void finishMotion(NumenPlayer companion, Motion m) {
        if (m == Motion.CROUCH || m == Motion.NOD) {
            SocialMotion.crouch(companion, false);
        }
        SocialMotion.clearLook(companion.getUUID());
    }
}
