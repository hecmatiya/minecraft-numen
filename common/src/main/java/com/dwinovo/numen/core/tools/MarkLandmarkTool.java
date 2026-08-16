package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.landmark.LandmarkRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * {@code mark_landmark} —— 给坐标/结构命名:"家""田""楼梯""床"……
 * 标记后每次会话自动注入该地标(名字+坐标+实时距离),从此指哪说哪。
 *
 * <p>三种标法:
 * <ul>
 *   <li>不传坐标 = 标记当前位置(点);</li>
 *   <li>传 x/y/z = 标记指定坐标(点,描述远处/看不到的地方);</li>
 *   <li>再传 x2/y2/z2 = 标记<b>矩形区域</b>(两个对角点)——田/牛圈这类
 *       有面积的地方标四个边界中的两个对角即可,区域距离按"到边界的
 *       最近距离"算(站在田里 = 0 格)。</li>
 * </ul>
 * 同名覆盖。纯客户端工具(读写存档级地标文件)。
 */
public final class MarkLandmarkTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String name, Integer x, Integer y, Integer z,
                        Integer x2, Integer y2, Integer z2) {}

    @Override
    public String name() {
        return "mark_landmark";
    }

    @Override
    public String description() {
        return "Give a place a name so it shows up every turn with its coordinates and distance: "
                + "mark_landmark(name=\"家\") marks where you stand; pass x/y/z to name a place you "
                + "can't see; pass x2/y2/z2 too to mark a RECTANGLE (two opposite corners) for "
                + "areas like 田/牛圈 — distance is then to the region's nearest edge (0 inside). "
                + "Injected every turn (nearest 32 blocks). Same name overwrites. "
                + "List with list_landmarks, forget with unmark_landmark.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("name", "The name for this place — short, e.g. 家/田/牛圈/楼梯/床.")
                .optionalInteger("x", "Block x of corner 1; omit to use where you stand.", -30_000_000, 30_000_000)
                .optionalInteger("y", "Block y of corner 1; omit to use where you stand.", -64, 1024)
                .optionalInteger("z", "Block z of corner 1; omit to use where you stand.", -30_000_000, 30_000_000)
                .optionalInteger("x2", "Block x of opposite corner 2 (optional: makes it a rectangle).", -30_000_000, 30_000_000)
                .optionalInteger("y2", "Block y of opposite corner 2 (optional).", -64, 1024)
                .optionalInteger("z2", "Block z of opposite corner 2 (optional).", -30_000_000, 30_000_000)
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        Args a;
        try {
            a = GSON.fromJson(call.args(), Args.class);
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail("invalid arguments: " + ex.getMessage()).toJson());
            return;
        }
        if (a == null || a.name() == null || a.name().isBlank()) {
            call.complete(TaskResult.fail("name 必填:给这个地方起个名字。").toJson());
            return;
        }
        boolean wantRect = a.x2() != null || a.y2() != null || a.z2() != null;
        Integer x = a.x();
        Integer y = a.y();
        Integer z = a.z();
        Integer x2 = a.x2();
        Integer y2 = a.y2();
        Integer z2 = a.z2();
        if (x == null || y == null || z == null) {
            var body = call.ctx().entity();
            if (body == null) {
                call.complete(TaskResult.fail("同伴不在当前加载范围,拿不到当前位置——请传 x/y/z 坐标。").toJson());
                return;
            }
            BlockPos p = body.blockPosition();
            x = p.getX();
            y = p.getY();
            z = p.getZ();
        }
        if (wantRect && (x2 == null || y2 == null || z2 == null)) {
            call.complete(TaskResult.fail("矩形需要两个对角:把 x2/y2/z2 补齐(或只传 x/y/z 标单点)。").toJson());
            return;
        }
        BlockPos corner1 = new BlockPos(x, y, z);
        BlockPos corner2 = wantRect ? new BlockPos(x2, y2, z2) : corner1;
        int count = LandmarkRegistry.record(call.ctx().entityUuid(), a.name(), corner1, corner2);
        boolean isPoint = corner1.equals(corner2);
        String where = isPoint
                ? "(" + x + "," + y + "," + z + ")"
                : "矩形 (" + Math.min(x, x2) + ".." + Math.max(x, x2) + ","
                        + Math.min(y, y2) + ".." + Math.max(y, y2) + ","
                        + Math.min(z, z2) + ".." + Math.max(z, z2) + ")";
        call.complete(TaskResult.ok("已标记 \"" + a.name().strip() + "\" " + where
                + "。现在共 " + count + " 个地标,之后每次说话都会带上它的坐标和距离。",
                Map.of("name", a.name().strip(), "x", x, "y", y, "z", z,
                        "x2", corner2.getX(), "y2", corner2.getY(), "z2", corner2.getZ(),
                        "total", count)).toJson());
    }
}
