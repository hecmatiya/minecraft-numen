package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * Typed descriptor for {@code harvest_crop} — the "gentle harvest" action.
 *
 * <p>Unlike {@code break_block} (which destroys the crop and forces a replant),
 * this mimics the vanilla villager's {@code HarvestFarmland} behaviour: only a
 * fully-grown crop is touched, its drops are rolled and spawned as if broken,
 * and the crop block itself stays in place with its {@code age} reset to 0 so it
 * regrows without replanting.
 *
 * <p>Supported: every vanilla {@code CropBlock} (wheat, carrots, potatoes,
 * beetroots, nether wart). Special crops (cocoa, sugarcane, melon, pumpkin) are
 * intentionally out of scope for v1 — the tool refuses them and points at
 * {@code break_block}.
 */
public final class HarvestCropTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "harvest_crop";

    /** The crop block to harvest. */
    public final BlockPos target;

    public HarvestCropTaskRecord(String toolCallId, long deadlineGameTime, BlockPos target) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.target = target.immutable();
    }

    @Override
    public String describe() {
        return TOOL_NAME + " @" + target.getX() + "," + target.getY() + "," + target.getZ();
    }
}
