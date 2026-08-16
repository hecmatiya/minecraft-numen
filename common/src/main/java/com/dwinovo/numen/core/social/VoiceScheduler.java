package com.dwinovo.numen.core.social;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.core.tools.SpeakTool;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 场景台词调度器——车万女仆"环境语音"的客户端移植:每 20 tick 检查一次
 * 每个在场同伴的情境,命中就把对应台词丢给 {@link SpeakTool#speakLine}
 * (聊天框 + 气泡 + 声线),由语音管线排队逐句播。
 *
 * <p>触发规则(全部带去重/冷却,不会碎碎念):
 * <ul>
 *   <li><b>早晨</b> 6:00~9:00(dayTime 0~3000):每个游戏日一次;</li>
 *   <li><b>傍晚</b> 18:00~21:00(dayTime 12000~15000):每个游戏日一次;</li>
 *   <li><b>下雨</b>:每 10 分钟最多一次;</li>
 *   <li><b>冷/热</b>(按她所在群系):每 10 分钟最多一次;</li>
 *   <li><b>受伤</b>(客户端 hurtTime 边沿):每 60 秒最多一次;</li>
 *   <li><b>主人回家</b>(主人走进 16 格,从 20 格外算起):每 5 分钟最多一次。</li>
 * </ul>
 *
 * <p>纯客户端:说给主人听的,主人不在场(level/player 为 null)自动跳过。
 */
public final class VoiceScheduler {

    private static final int CHECK_INTERVAL = 20;            // 每 20 tick 检查一次
    private static final long RAIN_COOLDOWN = 10 * 60 * 20L;     // 10 分钟
    private static final long CLIMATE_COOLDOWN = 10 * 60 * 20L;  // 10 分钟
    private static final long GREETING_COOLDOWN = 5 * 60 * 20L;  // 5 分钟
    private static final long HURT_COOLDOWN = 60 * 20L;          // 60 秒
    /** 刚说过"回来了"之后,多久内不再说早/晚安——避免回家时两句连着来。
     *  早晨/傍晚窗口各有 3 小时,错开几分钟完全来得及。 */
    private static final long GREETING_LINE_GAP = 3 * 60 * 20L;  // 3 分钟
    /** 回家边沿:她在家等你、你从 12 格外走进 8 格内才算"回家"。
     *  注意:她跟随/陪伴你时距离恒近,不会触发——一直在一起不需要欢迎。 */
    private static final double GREETING_ENTER = 8.0;
    private static final double GREETING_EXIT = 12.0;

    /** 每同伴的调度状态。 */
    private static final class State {
        long morningDay = -1;      // 上次说早安的游戏日(-1 = 还没说过)
        long eveningDay = -1;      // 上次说晚安的游戏日
        long lastRain = -RAIN_COOLDOWN;        // 负值 = 首次检查直接可用(无溢出)
        long lastClimate = -CLIMATE_COOLDOWN;
        long lastGreeting = -GREETING_COOLDOWN;
        long lastHurt = -HURT_COOLDOWN;
        boolean insideGreet = false;
        int prevHurtTime = 0;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static int tickCounter;

    private VoiceScheduler() {}

    /** 客户端每 tick 调用(由 mod 的客户端 tick 钩子驱动,与服务端无关)。 */
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (++tickCounter % CHECK_INTERVAL != 0) {
            return;
        }
        long now = level.getGameTime();
        long dayTime = level.getDefaultClockTime() % 24000L;
        long day = level.getDefaultClockTime() / 24000L;
        boolean raining = level.isRaining();

        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            Entity entity = level.getEntity(entry.uuid());
            if (!(entity instanceof LivingEntity companion)) {
                continue;   // 不在加载范围 = 不在场,不打扰
            }
            State st = STATES.computeIfAbsent(entry.uuid(), u -> new State());

            // 早晨:每天一次;刚打过招呼(回家)后 3 分钟内不说,错开时机。
            if (dayTime > 0 && dayTime < 3000 && st.morningDay != day
                    && now - st.lastGreeting >= GREETING_LINE_GAP) {
                st.morningDay = day;
                speak(entry, VoiceLines.Scene.MORNING);
                continue;
            }
            // 傍晚:每天一次;同上错开"回来了"。
            if (dayTime > 12000 && dayTime < 15000 && st.eveningDay != day
                    && now - st.lastGreeting >= GREETING_LINE_GAP) {
                st.eveningDay = day;
                speak(entry, VoiceLines.Scene.EVENING);
                continue;
            }
            // 下雨。
            if (raining && now - st.lastRain >= RAIN_COOLDOWN) {
                st.lastRain = now;
                speak(entry, VoiceLines.Scene.RAIN);
                continue;
            }
            // 冷/热(按她所在的位置判断)。
            BlockPos pos = companion.blockPosition();
            Biome biome = level.getBiome(pos).value();
            boolean cold = biome.coldEnoughToSnow(pos, level.getSeaLevel());
            boolean hot = level.environmentAttributes().getValue(
                    net.minecraft.world.attribute.EnvironmentAttributes.SNOW_GOLEM_MELTS, pos);
            if ((cold || hot) && now - st.lastClimate >= CLIMATE_COOLDOWN) {
                st.lastClimate = now;
                speak(entry, cold ? VoiceLines.Scene.COLD : VoiceLines.Scene.HOT);
                continue;
            }
            // 主人回家:从 12 格外走进 8 格内 = "新接触"(迟滞防抖)。
            double dist = companion.distanceTo(mc.player);
            boolean inside = dist <= GREETING_ENTER;
            if (inside && !st.insideGreet && now - st.lastGreeting >= GREETING_COOLDOWN) {
                st.lastGreeting = now;
                speak(entry, VoiceLines.Scene.GREETING);
                com.dwinovo.numen.core.Constants.LOG.debug(
                        "[voices] greeting for {} (dist {:.1f})", entry.uuid(), dist);
            }
            st.insideGreet = inside;
            if (!inside && dist > GREETING_EXIT) {
                st.insideGreet = false;   // 完全走出去,下次回来才算"回家"
            }
            // 受伤:客户端受击倒计时(hurtTime)的上升沿。
            int hurt = companion.hurtTime;
            if (hurt > 0 && st.prevHurtTime == 0 && now - st.lastHurt >= HURT_COOLDOWN) {
                st.lastHurt = now;
                speak(entry, VoiceLines.Scene.HURT);
            }
            st.prevHurtTime = hurt;
        }
    }

    private static void speak(NumenRoster.Entry entry, VoiceLines.Scene scene) {
        String line = VoiceLines.pick(scene);
        if (line != null) {
            SpeakTool.speakLine(entry.uuid(), line);
        }
    }
}
