package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.SleepTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code sleep} —— 睡觉,三档策略,一次调用搞定:
 * <ol>
 *   <li><b>床边</b>(床头 3 格内):当场躺下,即时返回;</li>
 *   <li><b>附近</b>({@value #AUTO_WALK_RADIUS} 格内):派发自动走近任务——
 *       身体自己导航到床边再睡(异步,回执 task_id,用 task_status 轮询);</li>
 *   <li><b>太远</b>:提示先 goto 到床边,不瞎跑。</li>
 * </ol>
 * 找床与长途赶路仍归 scan_blocks / goto——这里负责"躺下 + 确认睡着",
 * 外加把"回家默认站位离床几步"的别扭抹平。
 */
public final class SleepTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final SleepOps impl = new SleepOps();

    /** 这个半径内的床自动走过去睡;更远提示先 goto。 */
    private static final int AUTO_WALK_RADIUS = 16;
    /** 自动走近的预算(含走到 + 躺下,~2 分钟)。 */
    private static final long TIMEOUT_TICKS = 120 * 20;

    private record Args(Integer x, Integer y, Integer z) {}

    @Override
    public String name() {
        return "sleep";
    }

    @Override
    public String description() {
        return "Get into a bed and report whether you are actually asleep. Three modes in one call: "
                + "if you are standing next to a bed you lie down immediately; if a bed is within "
                + "16 blocks the body walks over and lies down by itself (async — poll task_status); "
                + "farther than that it tells you to goto the bed first. Give x/y/z to name a "
                + "specific bed, or omit them to use the nearest one. Succeeds only when the server "
                + "confirms you are sleeping; otherwise it hands back Minecraft's own reason — read "
                + "it. \"Only at night\" means wait (set_timer), not retry. Find beds with "
                + "scan_blocks using #minecraft:beds.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "X of the bed. Leave null to use the nearest bed.")
                .nullableNumber("y", "Y of the bed. Leave null to use the nearest bed.")
                .nullableNumber("z", "Z of the bed. Leave null to use the nearest bed.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        boolean coordsGiven = a != null && a.x() != null && a.y() != null && a.z() != null;
        BlockPos bedHead = coordsGiven
                ? SleepOps.headOf(self.level(), new BlockPos(a.x(), a.y(), a.z()))
                : SleepOps.nearestBedHead(self, AUTO_WALK_RADIUS);
        if (bedHead == null) {
            reply.accept(impl.noBed(self, coordsGiven));
            return;
        }

        // 床边:当场睡(即时返回)。
        if (SleepOps.withinSleepReach(self, bedHead)) {
            reply.accept(impl.sleepAt(bedHead, self));
            return;
        }

        // 附近:自动走过去再睡(异步任务,和 goto 一样的受理回执)。
        long deadline = ctx(toolCallId, self).deadline(TIMEOUT_TICKS);
        dispatchAsync(self, new SleepTaskRecord(toolCallId, deadline, bedHead), reply);
    }
}
