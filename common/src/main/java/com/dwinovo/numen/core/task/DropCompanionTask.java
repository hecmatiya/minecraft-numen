package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code drop_items} on the player body — toss items forward, natively. One-tick. */
public final class DropCompanionTask extends AbstractCompanionTask<DropItemsTaskRecord> {

    private int dropped;
    private String doneMessage = "done";

    public DropCompanionTask(NumenPlayer player, DropItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                () -> player.level() instanceof ServerLevel ? null
                        : new Precondition.Failure("not on a server level", FailureType.UNKNOWN),
                () -> PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("no " + r.label + " in inventory to drop",
                                FailureType.NO_MATERIAL));
    }

    @Override
    protected void onStart() {
        Inventory inv = player.getInventory();
        dropped = Math.min(r.count, PlayerInv.count(inv, r.item));

        // Toss like a real player: native Player.drop(stack, false) throws each stack in the facing
        // direction with vanilla motion + pickup delay and fires the drop event (mods watching item
        // tosses see it) — instead of hand-building an ItemEntity with a made-up velocity.
        //
        // 按槽位取背包里真实的那一叠(带完整 NBT),丢它的副本——附魔/耐久/自定义名
        // 全都随物品走。以前是 new ItemStack(item, n) 凭空造,附魔和耐久会丢。
        int remaining = dropped;
        for (int slot = 0; slot < inv.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty() || stack.getItem() != r.item) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            ItemStack toss = stack.copy();   // 完整副本:NBT 原样保留
            toss.setCount(take);
            stack.shrink(take);              // 背包里同步扣掉
            remaining -= take;
            player.drop(toss, false);
        }
        doneMessage = "dropped " + dropped + "x " + r.label
                + (dropped < r.count ? " (only had " + dropped + ")" : "");
        succeed();   // work is done — finalize this same tick, before a Stop can mislabel it
    }

    @Override
    protected TaskState onTick() {
        return TaskState.SUCCESS;
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("dropped", dropped);
        data.put("remaining_in_inventory", PlayerInv.count(player.getInventory(), r.item));
        return data;
    }

    @Override
    protected String successMessage() {
        return doneMessage;
    }

    @Override
    protected String timeoutMessage() {
        return "drop timed out unexpectedly";
    }

    @Override
    protected String cancelledMessage() {
        return "drop interrupted";
    }
}
