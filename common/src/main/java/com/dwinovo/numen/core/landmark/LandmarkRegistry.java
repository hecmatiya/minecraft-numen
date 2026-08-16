package com.dwinovo.numen.core.landmark;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 命名地标注册表:把坐标/结构标记成"家""田""楼梯"等名字,随存档持久化。
 *
 * <p>地标是<b>点或矩形区域</b>(AABB,点 = 对角相同),田/牛圈这类有面积的
 * 标两个对角点即可。数据存 <code>存档目录/numen/landmarks/&lt;companion-uuid&gt;.json</code>
 * (每个同伴一份)。每会话注入器({@link LandmarkWorldContext})读它,把地标
 * 及实时距离拼进世界状态——小爱和外部大脑都不用现扫就知道"家在哪"。
 *
 * <p>纯数据(名字+坐标),不碰实体;MCP 线程(工具)与主线程(注入)都访问,
 * 全程 synchronized + 写时落盘。存档级而非配置级:不同存档的家园不串。
 */
public final class LandmarkRegistry {

    /** 一个地标:名字 + 矩形区域(对角归一化;点 = 两端相同)。 */
    public record Entry(String name, int x1, int y1, int z1, int x2, int y2, int z2) {

        /** 点是矩形退化:对角相同。 */
        public boolean isPoint() {
            return x1 == x2 && y1 == y2 && z1 == z2;
        }

        /** 该点是否落在区域内。 */
        public boolean contains(double px, double py, double pz) {
            return px >= x1 && px <= x2 && py >= y1 && py <= y2 && pz >= z1 && z2 >= pz && pz <= z2;
        }

        /** 点到区域的最短距离平方(框内 = 0)。 */
        public double distanceSqTo(double px, double py, double pz) {
            double dx = Math.max(0, Math.max(x1 - px, px - x2));
            double dy = Math.max(0, Math.max(y1 - py, py - y2));
            double dz = Math.max(0, Math.max(z1 - pz, pz - z2));
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** 每同伴会话缓存(惰性加载,变更即落盘)。 */
    private static final Map<UUID, List<Entry>> CACHE = new HashMap<>();

    private LandmarkRegistry() {}

    /** 数据目录:存档根/numen/landmarks。非单机集成服务器返回 null(降级不注入)。 */
    private static Path dataDir() {
        try {
            var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            if (server == null) {
                return null;
            }
            return server.getWorldPath(LevelResource.ROOT).resolve("numen").resolve("landmarks");
        } catch (RuntimeException e) {
            // 非客户端环境(测试/纯服务端):没有 Minecraft 实例。
            return null;
        }
    }

    private static Path fileFor(UUID companionUuid) {
        Path dir = dataDir();
        return dir == null ? null : dir.resolve(companionUuid + ".json");
    }

    /** 全部地标(记录顺序)。文件不存在/非客户端 → 空表。 */
    public static synchronized List<Entry> all(UUID companionUuid) {
        List<Entry> cached = CACHE.get(companionUuid);
        if (cached != null) {
            return List.copyOf(cached);
        }
        List<Entry> loaded = load(companionUuid);
        CACHE.put(companionUuid, loaded);
        return List.copyOf(loaded);
    }

    /** 标记一个点地标(同名覆盖为新位置)。返回该同伴现在的地标总数。 */
    public static synchronized int record(UUID companionUuid, String name, BlockPos pos) {
        return record(companionUuid, name, pos, pos);
    }

    /** 标记一个矩形区域地标(两个对角点,自动归一化;同名覆盖)。 */
    public static synchronized int record(UUID companionUuid, String name, BlockPos a, BlockPos b) {
        int x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX());
        int y1 = Math.min(a.getY(), b.getY()), y2 = Math.max(a.getY(), b.getY());
        int z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ());
        List<Entry> entries = new ArrayList<>(all(companionUuid));
        String key = name.strip().toLowerCase(java.util.Locale.ROOT);
        entries.removeIf(e -> e.name().equals(key));
        entries.add(new Entry(key, x1, y1, z1, x2, y2, z2));
        CACHE.put(companionUuid, entries);
        save(companionUuid, entries);
        return entries.size();
    }

    /** 删除一个地标。返回是否删到了东西。 */
    public static synchronized boolean remove(UUID companionUuid, String name) {
        String key = name.strip().toLowerCase(java.util.Locale.ROOT);
        List<Entry> entries = new ArrayList<>(all(companionUuid));
        boolean changed = entries.removeIf(e -> e.name().equals(key));
        if (changed) {
            CACHE.put(companionUuid, entries);
            save(companionUuid, entries);
        }
        return changed;
    }

    // ---- 持久化 ----

    private static List<Entry> load(UUID companionUuid) {
        Path file = fileFor(companionUuid);
        if (file == null || !Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            var gson = new com.google.gson.Gson();
            var arr = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    com.google.gson.JsonArray.class);
            List<Entry> out = new ArrayList<>();
            if (arr != null) {
                for (var el : arr) {
                    var o = el.getAsJsonObject();
                    // 兼容旧格式(单点 x/y/z):缺 x2/y2/z2 时按点读。
                    int x1 = o.get("x").getAsInt();
                    int y1 = o.get("y").getAsInt();
                    int z1 = o.get("z").getAsInt();
                    int x2 = o.has("x2") ? o.get("x2").getAsInt() : x1;
                    int y2 = o.has("y2") ? o.get("y2").getAsInt() : y1;
                    int z2 = o.has("z2") ? o.get("z2").getAsInt() : z1;
                    out.add(new Entry(o.get("name").getAsString(), x1, y1, z1, x2, y2, z2));
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            com.dwinovo.numen.core.Constants.LOG.warn(
                    "[numen-landmark] 读地标失败(忽略,当空表): {}", String.valueOf(e));
            return new ArrayList<>();
        }
    }

    private static void save(UUID companionUuid, List<Entry> entries) {
        Path file = fileFor(companionUuid);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            var gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            var arr = new com.google.gson.JsonArray();
            for (Entry e : entries) {
                var o = new com.google.gson.JsonObject();
                o.addProperty("name", e.name());
                o.addProperty("x", e.x1());
                o.addProperty("y", e.y1());
                o.addProperty("z", e.z1());
                o.addProperty("x2", e.x2());
                o.addProperty("y2", e.y2());
                o.addProperty("z2", e.z2());
                arr.add(o);
            }
            Files.writeString(file, gson.toJson(arr), StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.dwinovo.numen.core.Constants.LOG.warn(
                    "[numen-landmark] 写地标失败: {}", String.valueOf(e));
        }
    }
}
