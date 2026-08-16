package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * Typed descriptor for {@code sleep} — the auto-walk-to-bed variant.
 *
 * <p>When the bed is not within vanilla sleep reach (3 blocks of the head),
 * {@code sleep} dispatches this task: the body walks to the bed on its own and
 * then lies down, so the owner does not have to micro-position her at the bed.
 */
public final class SleepTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "sleep";

    /** The bed HEAD position to walk to and sleep in. */
    public final BlockPos bedHead;

    public SleepTaskRecord(String toolCallId, long deadlineGameTime, BlockPos bedHead) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.bedHead = bedHead.immutable();
    }

    @Override
    public String describe() {
        return TOOL_NAME + " -> bed @" + bedHead.getX() + "," + bedHead.getY() + "," + bedHead.getZ();
    }
}
