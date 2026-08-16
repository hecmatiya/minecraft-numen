package com.dwinovo.numen.core.context;

import com.dwinovo.numen.agent.prompt.WorldContextProvider;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * 车万女仆式世界状态注入:主人每次说话,把身边环境拼进用户回合——
 * 女仆自身(位置/血量/饥饿/手持)、主人(血量/手持/距离)、附近实体
 * (半径 12 内的生物,最多 8 个)。模型不用先调感知工具就"看得到"周围,
 * 想深查再用工具。
 *
 * <p>内容刻意<b>轻量</b>:每轮都注入,长文本是浪费;它是提醒,不是调查。
 */
public final class NumenWorldContext implements WorldContextProvider {

    private static final double NEARBY_RADIUS = 12.0;
    private static final int MAX_NEARBY = 8;

    @Override
    public String id() {
        return "numen_world";
    }

    @Override
    public String describe() {
        return "主人说话时注入女仆自身状态、主人状态和附近生物(轻量提醒)";
    }

    @Override
    public String contextFor(NumenPlayer self) {
        StringBuilder sb = new StringBuilder();
        // 自身状态
        sb.append("我:").append(block(self)).append(",hp ").append((int) self.getHealth())
                .append('/').append((int) self.getMaxHealth())
                .append(",饥饿 ").append(self.getFoodData().getFoodLevel());
        String held = self.getMainHandItem().isEmpty()
                ? "空手" : self.getMainHandItem().getHoverName().getString();
        sb.append(",手持 ").append(held);
        // 主人状态
        ServerPlayer owner = self.resolveOwnerPlayer();
        if (owner != null && owner.level() == self.level()) {
            sb.append(";主人:").append(block(owner))
                    .append(",hp ").append((int) owner.getHealth()).append('/')
                    .append((int) owner.getMaxHealth())
                    .append(",距离 ").append((int) owner.distanceTo(self)).append(" 格");
            String oheld = owner.getMainHandItem().isEmpty()
                    ? "空手" : owner.getMainHandItem().getHoverName().getString();
            sb.append(",手持 ").append(oheld);
        }
        // 附近实体(生物,按距离取最近 8 个)
        AABB box = self.getBoundingBox().inflate(NEARBY_RADIUS);
        java.util.List<LivingEntity> nearby = new java.util.ArrayList<>();
        nearby.addAll(self.level().getEntitiesOfClass(Monster.class, box));
        nearby.addAll(self.level().getEntitiesOfClass(
                net.minecraft.world.entity.animal.Animal.class, box));
        nearby.sort(java.util.Comparator.comparingDouble(
                e -> e.distanceToSqr(self)));
        if (!nearby.isEmpty()) {
            sb.append(";附近:");
            int n = Math.min(MAX_NEARBY, nearby.size());
            for (int i = 0; i < n; i++) {
                LivingEntity e = nearby.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                String name = e.getType().getDescription().getString();
                sb.append(name).append(' ').append((int) e.distanceTo(self)).append(" 格");
            }
        }
        return sb.toString();
    }

    private static String block(net.minecraft.world.entity.Entity e) {
        var p = e.blockPosition();
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
