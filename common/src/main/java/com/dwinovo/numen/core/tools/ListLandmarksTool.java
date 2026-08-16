package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.landmark.LandmarkRegistry;
import com.dwinovo.numen.task.TaskResult;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code list_landmarks} —— 列出已标记的全部地标(名字+坐标+当前距离)。
 * 纯客户端工具。
 */
public final class ListLandmarksTool implements NumenTool {

    @Override
    public String name() {
        return "list_landmarks";
    }

    @Override
    public String description() {
        return "List every named landmark (家/田/床/…) with its coordinates and your current "
                + "distance to it, nearest first. Landmarks are also injected into every turn "
                + "automatically — use this when you need the full list or exact numbers.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void invoke(ToolCall call) {
        List<LandmarkRegistry.Entry> entries = LandmarkRegistry.all(call.ctx().entityUuid());
        if (entries.isEmpty()) {
            call.complete(TaskResult.ok("还没有标记任何地标。用 mark_landmark(name=...) 给位置起名,"
                    + "之后每次会话都会带上坐标和距离。").toJson());
            return;
        }
        var body = call.ctx().entity();
        List<LandmarkRegistry.Entry> sorted = entries.stream()
                .sorted(Comparator.comparingDouble(e -> body == null ? 0
                        : e.distanceSqTo(body.position().x, body.position().y, body.position().z)))
                .toList();
        StringBuilder sb = new StringBuilder("已标记 ").append(entries.size()).append(" 个地标(按距离):\n");
        for (LandmarkRegistry.Entry e : sorted) {
            String dist = body == null ? "?" : (int) Math.sqrt(
                    e.distanceSqTo(body.position().x, body.position().y, body.position().z)) + "格";
            // 与注入同款紧凑格式:点 (-173,124,949);矩形 (-161..-158,124,950..952)。
            sb.append("- ").append(e.name()).append(": (").append(compact(e))
                    .append(") 距离 ").append(dist).append('\n');
        }
        call.complete(TaskResult.ok(sb.toString().stripTrailing()).toJson());
    }

    /** 每维 {@code min..max},单值省略范围。 */
    private static String compact(LandmarkRegistry.Entry e) {
        return range(e.x1(), e.x2()) + "," + range(e.y1(), e.y2()) + "," + range(e.z1(), e.z2());
    }

    private static String range(int a, int b) {
        return a == b ? String.valueOf(a) : a + ".." + b;
    }
}
