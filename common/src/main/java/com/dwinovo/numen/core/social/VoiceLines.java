package com.dwinovo.numen.core.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwinovo.numen.core.Constants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 场景台词表——车万女仆"环境语音"的移植:按情境(早晨/傍晚/下雨/冷/热/
 * 受伤/主人回家)给同伴一句应景的话,由 {@link VoiceScheduler} 触发,经
 * {@code speak} 管线说出来(聊天框 + 气泡 + 声线)。
 *
 * <p>台词来源:内置默认(傲娇风)+ {@code config/numen/voices.json} 覆盖——
 * 文件里每个场景是一个字符串数组,播放时随机挑一条:
 * <pre>{
 *   "morning": ["哼,早安……才不是特意等你的。", "……"],
 *   "greeting": ["回来了?……哼,又不是在等你。"]
 * }</pre>
 * 想改人设/换角色,改 JSON 就行,不用动代码。
 */
public final class VoiceLines {

    /** 台词场景。 */
    public enum Scene {
        MORNING, EVENING, RAIN, COLD, HOT, HURT, GREETING
    }

    /** 内置默认台词(按 {@link Scene} 名索引)。 */
    private static final Map<Scene, List<String>> DEFAULT_LINES = new EnumMap<>(Scene.class);
    /** 配置覆盖后的台词(按 {@link Scene} 名索引)。 */
    private static final Map<Scene, List<String>> LINES = new EnumMap<>(Scene.class);

    private VoiceLines() {}

    static {
        DEFAULT_LINES.put(Scene.MORNING, List.of(
                "哼,早安。……才、才不是特意等你醒的。",
                "早上了。今天也要好好吃饭,笨蛋主人。",
                "唔…早安。本小姐可不是早起,只是刚好醒了。"));
        DEFAULT_LINES.put(Scene.EVENING, List.of(
                "天黑了,笨蛋主人早点回来。",
                "晚上了……该休息了,别熬夜。",
                "哼,这么晚还不回家,要本小姐担心吗?"));
        DEFAULT_LINES.put(Scene.RAIN, List.of(
                "下雨了呢……伞,带了吗?",
                "下雨天最适合窝在家里……你不准淋雨。",
                "啧,下雨了。衣服收了吗?"));
        DEFAULT_LINES.put(Scene.COLD, List.of(
                "好冷……笨蛋主人多穿点。",
                "呼……冷死了。你、你可别感冒了。"));
        DEFAULT_LINES.put(Scene.HOT, List.of(
                "热死了……这种天气还要出门?",
                "好热……水,喝了吗?别中暑了。"));
        DEFAULT_LINES.put(Scene.HURT, List.of(
                "呜……好痛!",
                "居、居然偷袭本小姐?!",
                "嘶……疼死了……"));
        DEFAULT_LINES.put(Scene.GREETING, List.of(
                "回来了?……哼,又不是在等你。",
                "欢迎回来,主人。",
                "你还知道回来啊……哼,饭在桌上。",
                "……回来了就好。"));
        for (Scene s : Scene.values()) {
            LINES.put(s, new ArrayList<>(DEFAULT_LINES.get(s)));
        }
    }

    /** 从 {@code config/numen/voices.json} 加载覆盖;文件缺失/损坏 = 用默认,不报错。 */
    public static void loadConfig(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                Scene scene = parseScene(e.getKey());
                if (scene == null || !e.getValue().isJsonArray()) {
                    continue;
                }
                List<String> lines = new ArrayList<>();
                for (JsonElement el : e.getValue().getAsJsonArray()) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (!s.isEmpty()) {
                            lines.add(s);
                        }
                    }
                }
                if (!lines.isEmpty()) {
                    LINES.put(scene, lines);
                }
            }
            Constants.LOG.info("[numen-core] voices.json loaded ({} scenes)", LINES.size());
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-core] failed to parse voices.json, using defaults: {}",
                    ex.toString());
        }
    }

    private static Scene parseScene(String key) {
        try {
            return Scene.valueOf(key.trim().toUpperCase());
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 随机挑一条台词;场景没配置任何台词返回 null(调用方跳过)。 */
    public static String pick(Scene scene) {
        List<String> lines = LINES.get(scene);
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        return lines.get((int) (Math.random() * lines.size()));
    }
}
