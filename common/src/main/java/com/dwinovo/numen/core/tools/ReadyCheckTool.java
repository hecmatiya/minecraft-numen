package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@code ready_check} —— "整装待发"的确定性检查(升级版):
 * <ol>
 *   <li><b>missing</b>:对照冒险清单(全身盔甲/武器/盾/耐久&gt;30% 的镐斧/
 *       垫脚方块/火把/食物/木板原木/水桶)报缺什么——阈值来自
 *       {@link ReadyConfig}(config/numen/ready.json);</li>
 *   <li><b>stow_candidates</b>:出门前该放家的东西,按优先级排好
 *       (贵重 → 垃圾 → 多余材料 → 多余食物 → 重复工具),每项带
 *       reason,LLM 照着搬;</li>
 *   <li><b>free_slots / stow_needed</b>:空位保障——目标
 *       {@code free_slots_target}(默认 12),不够列出还差几格。</li>
 * </ol>
 * 只检查不搬运;补全与存放由 agent 按 {@code minecraft-ready-up} 技能流程
 * 用仓库/合成/transfer 等工具执行。
 */
public final class ReadyCheckTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "ready_check";
    }

    @Override
    public String description() {
        return "Ready-up check (整装待发): deterministically audit this companion's equipment and "
                + "inventory against the adventure checklist. Returns: `missing` (what to fill: armor "
                + "pieces, weapon, shield, pickaxe/axe with durability, climbing blocks, torches, "
                + "food, logs, water bucket), `stow_candidates` (items to store at home before "
                + "leaving, by priority: valuables > junk > excess materials > excess food > "
                + "duplicate tools, each with a reason), and `free_slots`/`stow_needed` (free-slot "
                + "target so there is room for loot). This tool only REPORTS; use the "
                + "minecraft-ready-up skill workflow to fill gaps from storage, craft, stash at "
                + "home, and report to the owner.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        ReadyConfig cfg = ReadyConfig.get();
        JsonObject out = new JsonObject();
        List<String> missing = new ArrayList<>();
        JsonObject details = new JsonObject();

        // ---- 全身盔甲 ----
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = companion.getItemBySlot(slot);
            boolean has = !s.isEmpty();   // 装备槽只能放可装备物(1.21.5 组件校验)
            details.addProperty(slot.getName(), has ? itemLabel(s) : "none");
            if (!has) {
                missing.add(slot.getName());
            }
        }

        // ---- 武器(1.21.5 类扁平化:武器身份由 WEAPON 组件承载) ----
        ItemStack mainHand = companion.getMainHandItem();
        boolean weapon = mainHand.has(DataComponents.WEAPON);
        if (!weapon) {
            weapon = hasInInventory(companion, s -> s.has(DataComponents.WEAPON));
        }
        details.addProperty("weapon", weapon ? "ok" : "none");
        if (!weapon) {
            missing.add("weapon");
        }

        // ---- 盾牌 ----
        boolean shield = companion.getOffhandItem().is(Items.SHIELD)
                || hasInInventory(companion, s -> s.is(Items.SHIELD));
        details.addProperty("shield", shield ? "ok" : "none");
        if (!shield) {
            missing.add("shield");
        }

        // ---- 镐/斧(按工具的挖掘规则判断,耐久 > 阈值) ----
        checkTool(companion, cfg, details, missing, "pickaxe",
                s -> isToolFor(s, BlockTags.MINEABLE_WITH_PICKAXE));
        checkTool(companion, cfg, details, missing, "axe",
                s -> isToolFor(s, BlockTags.MINEABLE_WITH_AXE));

        // ---- 消耗品 ----
        int blocks = countItems(companion, s -> s.is(Items.COBBLESTONE)
                || s.is(Items.DIRT) || s.is(Items.COBBLED_DEEPSLATE)
                || s.is(ItemTags.PLANKS));
        details.addProperty("climbing_blocks", blocks);
        if (blocks < cfg.climbingBlocks) {
            missing.add("climbing_blocks(" + blocks + "/" + cfg.climbingBlocks + ")");
        }

        int torches = countItems(companion, s -> s.is(Items.TORCH));
        details.addProperty("torches", torches);
        if (torches < cfg.torches) {
            missing.add("torches(" + torches + "/" + cfg.torches + ")");
        }

        int food = countItems(companion, s -> {
            var foodComp = s.get(DataComponents.FOOD);
            return foodComp != null && foodComp.nutrition() >= 4;
        });
        details.addProperty("food", food);
        if (food < cfg.food) {
            missing.add("food(" + food + "/" + cfg.food + ")");
        }

        int logs = countItems(companion, s -> s.is(ItemTags.LOGS));
        details.addProperty("logs", logs);
        if (logs < cfg.logs) {
            missing.add("logs(" + logs + "/" + cfg.logs + ")");
        }

        int buckets = countItems(companion, s -> s.is(Items.WATER_BUCKET));
        details.addProperty("water_bucket", buckets);
        if (buckets < cfg.waterBucket) {
            missing.add("water_bucket");
        }

        // ---- 存放候选(按优先级) + 空位保障 ----
        List<StowCandidate> candidates = buildStowCandidates(companion, cfg);
        int freeSlots = freeSlots(companion);
        int stowNeeded = Math.max(0, cfg.freeSlotsTarget - freeSlots);

        JsonArray candArr = new JsonArray();
        for (StowCandidate c : candidates) {
            JsonObject o = new JsonObject();
            o.addProperty("slot", c.slot);
            o.addProperty("item", c.item);
            o.addProperty("count", c.count);
            o.addProperty("reason", c.reason);
            candArr.add(o);
        }

        // ---- 汇总 ----
        boolean ready = missing.isEmpty() && stowNeeded == 0;
        out.addProperty("ready", ready);
        out.addProperty("missing_count", missing.size());
        JsonArray missArr = new JsonArray();
        for (String m : missing) {
            missArr.add(m);
        }
        out.add("missing", missArr);
        out.add("details", details);
        out.addProperty("free_slots", freeSlots);
        out.addProperty("free_slots_target", cfg.freeSlotsTarget);
        out.addProperty("stow_needed", stowNeeded);
        out.add("stow_candidates", candArr);
        StringBuilder sb = new StringBuilder();
        if (!missing.isEmpty()) {
            sb.append("缺 ").append(missing.size()).append(" 项: ").append(String.join(", ", missing)).append("; ");
        }
        if (stowNeeded > 0) {
            sb.append("空位 ").append(freeSlots).append("/").append(cfg.freeSlotsTarget)
                    .append(",按 stow_candidates 放家腾出 ").append(stowNeeded).append(" 格; ");
        }
        if (sb.length() == 0) {
            sb.append("装备齐全,空位充足,可以出发。");
        }
        out.addProperty("summary", sb.toString().trim());
        reply.accept(GSON.toJson(out));
    }

    // ------------------------------------------------------------------
    // 存放候选:贵重 → 垃圾 → 多余材料 → 多余食物 → 重复工具
    // ------------------------------------------------------------------

    private record StowCandidate(int slot, String item, int count, String reason) {}

    private static List<StowCandidate> buildStowCandidates(NumenPlayer companion, ReadyConfig cfg) {
        List<StowCandidate> out = new ArrayList<>();
        var inv = companion.getInventory();

        // 预统计"留一组"材料的全背包总量(单槽最多 64,超量只能按总量判断)。
        Map<String, Integer> keepOneTotal = new java.util.HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (cfg.stowKeepOne.contains(id)) {
                keepOneTotal.merge(id, s.getCount(), Integer::sum);
            }
        }

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            String reason = null;

            // 重复工具:每类保留耐久最高的,其余放家
            String kind = toolKind(s);
            if (kind != null) {
                if (!isBestToolOfKind(companion, i, kind)) {
                    reason = "duplicate_tool";
                }
            }
            if (reason == null && cfg.stowValuables.contains(id)) {
                reason = "valuable";
            }
            if (reason == null && cfg.stowJunk.contains(id)) {
                reason = "junk";
            }
            // 留一组:总量 > 64 时该物品的全部槽位都标(搬走 64 以外的部分)。
            if (reason == null && cfg.stowKeepOne.contains(id)
                    && keepOneTotal.getOrDefault(id, 0) > 64) {
                reason = "excess_material(留64)";
            }
            if (reason == null) {
                var foodComp = s.get(DataComponents.FOOD);
                if (foodComp != null && s.getCount() > cfg.foodMax) {
                    reason = "excess_food";
                }
            }
            if (reason != null) {
                out.add(new StowCandidate(i, id, s.getCount(), reason));
            }
        }
        // 优先级排序:valuable < junk < excess_material < excess_food < duplicate_tool
        out.sort(Comparator.comparingInt(c -> priority(c.reason)));
        return out;
    }

    private static int priority(String reason) {
        return switch (reason) {
            case "valuable" -> 0;
            case "junk" -> 1;
            case "excess_material" -> 2;
            case "excess_food" -> 3;
            case "duplicate_tool" -> 4;
            default -> 5;
        };
    }

    /**
     * 工具分类键:pickaxe / axe / weapon / shield,不是工具返回 null。
     * 1.21.5 物品类扁平化,靠挖掘规则(tool rules)与数据组件判断。
     */
    private static String toolKind(ItemStack s) {
        if (isToolFor(s, BlockTags.MINEABLE_WITH_PICKAXE)) {
            return "pickaxe";
        }
        if (isToolFor(s, BlockTags.MINEABLE_WITH_AXE)) {
            return "axe";
        }
        if (s.has(DataComponents.WEAPON)) {
            return "weapon";
        }
        if (s.is(Items.SHIELD)) {
            return "shield";
        }
        return null;
    }

    /** 该工具的挖掘规则是否覆盖某可挖 tag(如 mineable/pickaxe)。 */
    private static boolean isToolFor(ItemStack s, net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag) {
        Tool tool = s.get(DataComponents.TOOL);
        if (tool == null) {
            return false;
        }
        for (Tool.Rule rule : tool.rules()) {
            for (net.minecraft.core.Holder<net.minecraft.world.level.block.Block> h : rule.blocks()) {
                if (h.is(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 同类工具里,槽位 {@code slot} 是否耐久最高(是 = 保留,否 = 放家)。 */
    private static boolean isBestToolOfKind(NumenPlayer companion, int slot, String kind) {
        ItemStack self = companion.getInventory().getItem(slot);
        int selfDur = self.getMaxDamage() - self.getDamageValue();
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == slot) {
                continue;
            }
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !kind.equals(toolKind(s))) {
                continue;
            }
            if (s.getMaxDamage() - s.getDamageValue() > selfDur) {
                return false;
            }
        }
        return true;
    }

    private static int freeSlots(NumenPlayer companion) {
        int free = 0;
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    // ------------------------------------------------------------------

    private static void checkTool(NumenPlayer companion, ReadyConfig cfg, JsonObject details,
                                  List<String> missing, String label, Predicate<ItemStack> pred) {
        ItemStack best = null;
        int bestDur = Integer.MIN_VALUE;
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !pred.test(s)) {
                continue;
            }
            int d = s.getMaxDamage() - s.getDamageValue();
            if (d > bestDur) {
                bestDur = d;
                best = s;
            }
        }
        if (best == null) {
            details.addProperty(label, "none");
            missing.add(label);
            return;
        }
        int max = best.getMaxDamage();
        int left = best.getMaxDamage() - best.getDamageValue();
        boolean ok = max <= 0 || (double) left / max >= cfg.toolMinDurability;
        details.addProperty(label, itemLabel(best) + " " + left + "/" + max);
        if (!ok) {
            missing.add(label + "_low(" + left + "/" + max + ")");
        }
    }

    private static boolean hasInInventory(NumenPlayer companion, Predicate<ItemStack> pred) {
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && pred.test(s)) {
                return true;
            }
        }
        return false;
    }

    private static int countItems(NumenPlayer companion, Predicate<ItemStack> pred) {
        int total = 0;
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && pred.test(s)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static String itemLabel(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }
}
