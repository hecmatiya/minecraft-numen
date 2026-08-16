package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.task.EquipCompanionTask;
import com.dwinovo.numen.core.task.EquipTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): equip an item (tool/weapon/armor/accessory) from the inventory. */
public final class EquipItemTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryTools impl = new InventoryTools();

    private record Args(String item_id, String slot) {}

    @Override
    public String name() {
        return "equip_item";
    }

    @Override
    public String description() {
        return "Equip an item from your OWN inventory: tool/weapon to the main hand, armor and modded "
                + "accessories (Curios/Trinkets) auto-routed to their slots. Omit slot for auto-routing; "
                + "set it only to force a hand or a specific armor piece. The previous item is stowed back. "
                + "Instant: equipping does NOT interrupt standing tasks (follow/company keep running).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to equip; must be in the inventory.")
                .optionalEnum("slot", "Optional target slot; omit to auto-route by item type. "
                        + "Use only to force a hand or a specific armor piece.",
                        "mainhand", "offhand", "head", "chest", "legs", "feet")
                .build();
    }

    /**
     * 装备是<b>单刻即时动作</b>(整个工作在 {@link EquipCompanionTask#onStart} 完成),所以
     * 直通执行、不进任务队列——standing 任务(跟随/陪伴)正在跑时换装备也不会把它顶掉。
     * (旧实现走 {@code enqueue}:新任务派发时 {@code preemptStanding} 会把常驻跟随顶掉,
     * 小爱每次换剑跟随就被无声取消,她还以为"跟随又被掐了"。)
     *
     * <p>身体被正经长活(mine/attack…)占着时仍拒绝,把选择权丢回给 LLM——与
     * {@code enqueue} 的占用闸门同一语义。
     */
    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        // InventoryTools.equipItem 保证返回 EquipTaskRecord(声明返回 TaskRecord 而已)。
        EquipTaskRecord record = (EquipTaskRecord) impl.equipItem(
                a.item_id(), a.slot(), ctx(toolCallId, companion));
        TaskRecord busy = CompanionTickDispatcher.asyncTaskFor(companion.getUUID());
        if (busy != null && !busy.isStanding()) {
            reply.accept(TaskResult.fail("身体正忙: " + busy.publicId() + "(" + busy.describe()
                    + ") 后台进行中。先 task_stop 叫停它,再换装备。").toJson());
            return;
        }
        EquipCompanionTask task = new EquipCompanionTask(companion, record);
        task.start();
        TaskState st = task.tick();
        if (st == TaskState.RUNNING) {
            // 兜底:equip 定义上单刻完成(onStart 全路径收尾),走不到这里。
            // 万一将来有耗时的装备流程,退回队列照常执行。
            CompanionTickDispatcher.queueFor(companion.getUUID()).enqueue(record);
            reply.accept(TaskResult.ok("装备流程启动了,后台执行中。").toJson());
            return;
        }
        reply.accept(task.buildResult(st).toJson());
    }
}
