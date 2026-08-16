package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.HarvestCropTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.function.Consumer;

/**
 * World-action tool: gently harvest ONE fully-grown crop. Walks to it
 * automatically, spawns the drops (the body picks them up), and resets the
 * crop's age to 0 so it regrows without replanting — the villager trick.
 */
public final class HarvestCropTool implements NumenTool {

    private static final Gson GSON = new Gson();
    /** Budget for walking to the crop plus the harvest itself (~1 min). */
    private static final long TIMEOUT_TICKS = 60 * 20;

    private record Args(int x, int y, int z) {}

    @Override
    public String name() {
        return "harvest_crop";
    }

    @Override
    public String description() {
        return "Gently harvest ONE fully-grown crop at the given position: the drops spawn like a "
                + "break, but the crop itself stays and resets to age 0 — it regrows without "
                + "replanting (the villager trick). Only mature crops are harvested; a not-yet-grown "
                + "crop fails and stays untouched. Handles wheat/carrots/potatoes/beetroots/nether "
                + "wart. Special crops (cocoa/sugarcane/melon/pumpkin) are NOT supported — use "
                + "break_block for those. Walks to the crop automatically; the body picks up the "
                + "drops itself. Use scan_blocks to find mature crops first, or inspect_block to "
                + "check a crop's age (age == max means ready).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "X of the crop block.")
                .integer("y", "Y of the crop block.")
                .integer("z", "Z of the crop block.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        BlockPos pos = new BlockPos(a.x(), a.y(), a.z());
        long deadline = ctx(toolCallId, companion).deadline(TIMEOUT_TICKS);
        enqueue(companion, new HarvestCropTaskRecord(toolCallId, deadline, pos), reply);
    }
}
