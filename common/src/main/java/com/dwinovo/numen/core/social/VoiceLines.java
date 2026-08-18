package com.dwinovo.numen.core.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwinovo.numen.core.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 场景台词表——车万女仆"环境语音"的移植:按情境(早晨/傍晚/下雨/冷/热/
 * 受伤/主人回家)给同伴一句应景的话,由 {@link VoiceScheduler} 触发,经
 * {@code speak} 管线说出来(聊天框 + 气泡 + 声线)。
 *
 * <p>台词来源,按角色挑、优先级从高到低:
 * <ol>
 *   <li>{@code config/numen/voices-<角色名>.json} —— 该角色的专属台词,
 *       文件名与召唤名大小写不敏感匹配(如 voices-deepchan.json 只给 deepchan 用);</li>
 *   <li>{@code config/numen/voices.json} —— 全局覆盖;</li>
 *   <li>内置默认(傲娇风)。</li>
 * </ol>
 * 每个文件里每个场景是一个字符串数组,播放时随机挑一条:
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
    /** 全局配置覆盖后的台词(voices.json,按 {@link Scene} 名索引)。 */
    private static final Map<Scene, List<String>> LINES = new EnumMap<>(Scene.class);
    /** 角色专属台词(角色名小写 → 场景 → 台词)。 */
    private static final Map<String, Map<Scene, List<String>>> COMPANION_LINES = new HashMap<>();

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

    /**
     * 从 {@code config/numen} 目录加载台词配置:{@code voices.json} 覆盖全局表,
     * {@code voices-<角色名>.json} 进角色专属表。
     * 兼容旧用法:传入的若是文件路径(非目录),按全局覆盖处理。缺失/损坏 = 用默认,不报错。
     */
    public static void loadConfig(Path dir) {
        if (dir == null) {
            return;
        }
        boolean isDir = Files.isDirectory(dir);
        Path globalFile = isDir ? dir.resolve("voices.json") : dir;
        Map<Scene, List<String>> global = readScenes(globalFile);
        if (global != null) {
            for (Map.Entry<Scene, List<String>> e : global.entrySet()) {
                LINES.put(e.getKey(), e.getValue());
            }
            Constants.LOG.info("[numen-core] voices.json loaded ({} scenes)", global.size());
        }
        if (!isDir) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.toLowerCase().startsWith("voices-") && n.endsWith(".json");
            }).forEach(p -> {
                String n = p.getFileName().toString();
                String name = n.substring("voices-".length(), n.length() - ".json".length()).trim();
                if (name.isEmpty()) {
                    return;
                }
                Map<Scene, List<String>> scenes = readScenes(p);
                if (scenes != null && !scenes.isEmpty()) {
                    COMPANION_LINES.put(name.toLowerCase(), scenes);
                    Constants.LOG.info("[numen-core] voices-{}.json loaded ({} scenes)",
                            name, scenes.size());
                }
            });
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-core] failed to scan voices-*.json: {}", ex.toString());
        }
    }

    /** 读一个台词 JSON;文件缺失/损坏返回 null(调用方回落默认)。 */
    private static Map<Scene, List<String>> readScenes(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<Scene, List<String>> scenes = new EnumMap<>(Scene.class);
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
                    scenes.put(scene, lines);
                }
            }
            return scenes;
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-core] failed to parse {}: {}",
                    file.getFileName(), ex.toString());
            return null;
        }
    }

    private static Scene parseScene(String key) {
        try {
            return Scene.valueOf(key.trim().toUpperCase());
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 全局随机挑一条(不区分角色);场景没配置任何台词返回 null(调用方跳过)。 */
    public static String pick(Scene scene) {
        return pickFrom(LINES, scene);
    }

    /** 按角色挑台词:先查该角色专属表,没有就回落全局表。 */
    public static String pick(String companionName, Scene scene) {
        if (companionName != null) {
            Map<Scene, List<String>> own = COMPANION_LINES.get(companionName.toLowerCase());
            if (own != null) {
                String line = pickFrom(own, scene);
                if (line != null) {
                    return line;
                }
            }
        }
        return pickFrom(LINES, scene);
    }

    private static String pickFrom(Map<Scene, List<String>> table, Scene scene) {
        List<String> lines = table.get(scene);
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        return lines.get((int) (Math.random() * lines.size()));
    }
}
