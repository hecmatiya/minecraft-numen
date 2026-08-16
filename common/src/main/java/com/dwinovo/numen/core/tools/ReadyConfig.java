package com.dwinovo.numen.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwinovo.numen.core.Constants;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code ready_check} 的阈值与清单配置——从 {@code config/numen/ready.json}
 * 加载,缺字段用内置默认。字段:
 * <pre>
 * {
 *   "torches": 16, "climbing_blocks": 32, "food": 16, "logs": 16,
 *   "water_bucket": 1, "tool_min_durability": 0.3,
 *   "free_slots_target": 12, "food_max": 32, "climbing_blocks_max": 128,
 *   "stow_valuables": ["minecraft:diamond", "minecraft:emerald", ...],
 *   "stow_keep_one": ["minecraft:iron_ingot", ...],
 *   "stow_junk": ["minecraft:cobblestone", ...]
 * }
 * </pre>
 */
public final class ReadyConfig {

    public int torches = 16;
    public int climbingBlocks = 32;
    public int food = 16;
    public int logs = 16;
    public int waterBucket = 1;
    public double toolMinDurability = 0.30;
    public int freeSlotsTarget = 12;
    public int foodMax = 32;
    public int climbingBlocksMax = 128;
    public java.util.Set<String> stowValuables = new java.util.HashSet<>();
    public java.util.Set<String> stowKeepOne = new java.util.HashSet<>();
    public java.util.Set<String> stowJunk = new java.util.HashSet<>();

    private static final ReadyConfig INSTANCE = new ReadyConfig();

    static {
        // 默认贵重物:全放家
        INSTANCE.stowValuables.addAll(java.util.List.of(
                "minecraft:diamond", "minecraft:emerald", "minecraft:gold_ingot",
                "minecraft:netherite_scrap", "minecraft:ender_pearl",
                "minecraft:experience_bottle", "minecraft:enchanted_book",
                "minecraft:elytra", "minecraft:totem_of_undying",
                "minecraft:nether_star", "minecraft:music_disc_13",
                "minecraft:dragon_breath"));
        // 默认"留一组"材料:超出一组的部分放家
        INSTANCE.stowKeepOne.addAll(java.util.List.of(
                "minecraft:iron_ingot", "minecraft:coal", "minecraft:redstone",
                "minecraft:copper_ingot", "minecraft:lapis_lazuli",
                "minecraft:gold_nugget", "minecraft:quartz"));
        // 默认垃圾:直接放家
        INSTANCE.stowJunk.addAll(java.util.List.of(
                "minecraft:cobblestone", "minecraft:stone", "minecraft:dirt",
                "minecraft:deepslate", "minecraft:gravel", "minecraft:sand",
                "minecraft:bone", "minecraft:rotten_flesh", "minecraft:string",
                "minecraft:flint", "minecraft:feather", "minecraft:poppy",
                "minecraft:dandelion", "minecraft:wheat_seeds"));
    }

    private ReadyConfig() {}

    public static ReadyConfig get() {
        return INSTANCE;
    }

    public static void load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            ReadyConfig c = INSTANCE;
            c.torches = intOr(o, "torches", c.torches);
            c.climbingBlocks = intOr(o, "climbing_blocks", c.climbingBlocks);
            c.food = intOr(o, "food", c.food);
            c.logs = intOr(o, "logs", c.logs);
            c.waterBucket = intOr(o, "water_bucket", c.waterBucket);
            c.toolMinDurability = doubleOr(o, "tool_min_durability", c.toolMinDurability);
            c.freeSlotsTarget = intOr(o, "free_slots_target", c.freeSlotsTarget);
            c.foodMax = intOr(o, "food_max", c.foodMax);
            c.climbingBlocksMax = intOr(o, "climbing_blocks_max", c.climbingBlocksMax);
            if (o.has("stow_valuables")) c.stowValuables = stringSet(o, "stow_valuables");
            if (o.has("stow_keep_one")) c.stowKeepOne = stringSet(o, "stow_keep_one");
            if (o.has("stow_junk")) c.stowJunk = stringSet(o, "stow_junk");
            Constants.LOG.info("[numen-core] ready.json loaded");
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-core] failed to parse ready.json, using defaults: {}",
                    ex.toString());
        }
    }

    private static int intOr(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static double doubleOr(JsonObject o, String key, double def) {
        try {
            return o.has(key) ? o.get(key).getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static java.util.Set<String> stringSet(JsonObject o, String key) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.get(key).getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
    }
}
