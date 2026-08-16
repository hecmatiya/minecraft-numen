package com.dwinovo.numen.core.context;

import com.dwinovo.numen.agent.prompt.WorldContextProvider;
import com.dwinovo.numen.core.landmark.LandmarkRegistry;
import com.dwinovo.numen.entity.NumenPlayer;

import java.util.Comparator;
import java.util.List;

/**
 * 地标注入:主人每次说话,把<b>足够近</b>的已标记地标连同实时距离拼进世界状态——
 * {@code 地标:家(-173,124,951) 5格,田(-180,124,940) 12格}。
 * 模型不用现扫就知道"家/田/床/楼梯在哪",指哪说哪。
 *
 * <p>只注入距离 ≤ {@link #INJECT_RADIUS} 的地标:走远了就不报(省 token,
 * 也避免"家在一千格外还天天报坐标"的噪音),回到范围内自然重新出现。
 * 没标过地标或附近没有 → 不注入(零开销)。
 */
public final class LandmarkWorldContext implements WorldContextProvider {

    /** 距离超过这个就不注入(格)。 */
    private static final double INJECT_RADIUS = 32.0;

    /** 范围内最多列多少个(近的在前;32 格内一般远用不满)。 */
    private static final int MAX_SHOWN = 16;

    @Override
    public String id() {
        return "landmarks";
    }

    @Override
    public String describe() {
        return "主人说话时注入已标记地标(名字+坐标+实时距离,按距离排序)";
    }

    @Override
    public String contextFor(NumenPlayer self) {
        List<LandmarkRegistry.Entry> entries = LandmarkRegistry.all(self.getUUID());
        if (entries.isEmpty()) {
            return null;   // 没标过地标:不注入,省 token
        }
        // 只注入 32 格内的地标,按距离排序,近的在前(范围内最多列一批)。
        List<LandmarkRegistry.Entry> nearest = entries.stream()
                .filter(e -> distSq(self, e) <= INJECT_RADIUS * INJECT_RADIUS)
                .sorted(Comparator.comparingDouble(e -> distSq(self, e)))
                .limit(MAX_SHOWN)
                .toList();
        if (nearest.isEmpty()) {
            return null;   // 附近没有地标:不注入
        }
        StringBuilder sb = new StringBuilder("地标:");
        for (LandmarkRegistry.Entry e : nearest) {
            if (sb.length() > 4) {
                sb.append(",");
            }
            sb.append(e.name()).append('(').append(compact(e))
                    .append(") ").append((int) Math.sqrt(distSq(self, e))).append("格");
        }
        return sb.toString();
    }

    /**
     * 坐标的紧凑表示:每维 {@code min..max},只有一个值时省略范围——
     * {@code 家(-173,124,949)};{@code 田(-161..-158,124,950..952)}。
     */
    private static String compact(LandmarkRegistry.Entry e) {
        return range(e.x1(), e.x2()) + "," + range(e.y1(), e.y2()) + "," + range(e.z1(), e.z2());
    }

    private static String range(int a, int b) {
        return a == b ? String.valueOf(a) : a + ".." + b;
    }

    private static double distSq(NumenPlayer self, LandmarkRegistry.Entry e) {
        var p = self.position();
        return e.distanceSqTo(p.x, p.y, p.z);
    }
}
