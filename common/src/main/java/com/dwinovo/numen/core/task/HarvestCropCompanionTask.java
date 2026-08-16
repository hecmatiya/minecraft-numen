package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.mixin.CropBlockAccessor;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.GoToThenDoTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code harvest_crop} on the player body — walk within reach of a fully-grown
 * crop and "gentle-harvest" it: roll the drops as if broken, spawn them in the
 * world (the body picks them up like any player), then reset the crop's age to 0
 * so it regrows in place. This is the vanilla villager's {@code HarvestFarmland}
 * behaviour: the crop block itself is never destroyed, so no replant is needed.
 */
public final class HarvestCropCompanionTask extends GoToThenDoTask<HarvestCropTaskRecord> {

    private static final double REACH = 4.5;
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;

    private boolean harvested;
    /** Drop path → count, for the result envelope. */
    private final Map<String, Integer> drops = new HashMap<>();

    public HarvestCropCompanionTask(NumenPlayer player, HarvestCropTaskRecord record) {
        super(player, record);
    }

    @Override
    protected PlayerNav buildNav() {
        // Walk to the crop and stop beside it: arrival = within working reach.
        return new PlayerNav(player, () -> r.target, WALK_SPEED, this::inReach);
    }

    @Override
    protected boolean reached() {
        return inReach();
    }

    private boolean inReach() {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(r.target)) <= REACH_SQR;
    }

    @Override
    protected TaskState act() {
        if (harvested) {
            return TaskState.SUCCESS;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = r.target;
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Only vanilla CropBlock subclasses are gentle-harvestable (wheat, carrots,
        // potatoes, beetroots, nether wart). Cocoa/sugarcane/melon/pumpkin are not
        // CropBlocks — refuse with a pointer to break_block instead of silently
        // breaking them like a normal dig.
        if (!(block instanceof CropBlock crop)) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            fail("harvest_crop only handles vanilla crops (wheat/carrots/potatoes/beetroots/"
                    + "nether_wart); " + id + " at " + pos.getX() + "," + pos.getY() + ","
                    + pos.getZ() + " is not one — use break_block for it.", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }

        if (!crop.isMaxAge(state)) {
            fail("crop at " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + " is not fully grown yet (age " + crop.getAge(state) + "/" + crop.getMaxAge()
                    + ") — only mature crops are harvested; let it grow.", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }

        // Gentle harvest: roll the drops as if broken bare-handed, spawn them in
        // the world (the body picks them up like any player would), then reset the
        // crop's age to 0 so it regrows in place.
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        List<ItemStack> stacks;
        try {
            stacks = Block.getDrops(state, level, pos, blockEntity, player, ItemStack.EMPTY);
        } catch (RuntimeException broken) {
            fail("harvest failed: " + broken.getMessage(), FailureType.INTERNAL);
            return TaskState.FAILED;
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            drops.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                    stack.getCount(), Integer::sum);
            ItemEntity itemEntity = new ItemEntity(level,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack.copy());
            itemEntity.setDeltaMovement(0, 0.1, 0);
            level.addFreshEntity(itemEntity);
        }

        IntegerProperty ageProperty = ((CropBlockAccessor) (Object) crop).numen$getAgeProperty();
        BlockState reset = state.hasProperty(ageProperty)
                ? state.setValue(ageProperty, 0)
                : state;
        level.setBlock(pos, reset, Block.UPDATE_ALL);
        harvested = true;
        return TaskState.SUCCESS;
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("harvested", harvested);
        data.put("x", r.target.getX());
        data.put("y", r.target.getY());
        data.put("z", r.target.getZ());
        data.put("drops", new HashMap<>(drops));
        return data;
    }

    @Override
    protected String successMessage() {
        return "harvested crop at " + r.target.getX() + "," + r.target.getY() + ","
                + r.target.getZ() + (drops.isEmpty() ? "" : " — " + drops);
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before reaching/harvesting crop at "
                + r.target.getX() + "," + r.target.getY() + "," + r.target.getZ();
    }

    @Override
    protected String cancelledMessage() {
        return "harvest_crop interrupted";
    }
}
