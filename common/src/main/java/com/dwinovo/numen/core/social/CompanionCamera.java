package com.dwinovo.numen.core.social;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 同伴视角相机(F8)——纯客户端功能:按 F8 在「自己 → 视野内的同伴们 → 自己」
 * 之间轮换渲染视角,相机挂到同伴眼睛上——所见即她所见,此时 F2 截图拍的正是
 * 她的视角;再按回到自己。
 *
 * <p>档一设计(不跟随拉区块):只有「客户端当前已加载」的同伴(实体在客户端
 * 实体表里)才进轮换名单——她得在你的渲染范围附近。她不在视野里时按 F8 只
 * 会切回自己并提示走近点。
 *
 * <p>纯客户端:不动服务端、不影响同伴 AI 与任何玩法;同伴死亡/换维度/离开
 * 视野时自动弹回自己的视角。
 */
public final class CompanionCamera {

    /** F8:原版未占用的键,轮换「自己 ↔ 同伴」视角。名字/分类用字面量,不依赖 lang 表。 */
    public static final KeyMapping KEY = new KeyMapping(
            "同伴视角",
            InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_F8,
            KeyMapping.Category.MISC);

    /** 当前轮换位置:0 = 自己;否则为上一次 cycle 里的候选下标。 */
    private static int camIdx = 0;
    /** 当前挂在谁身上(自己 = null)。 */
    private static UUID camUuid = null;

    private CompanionCamera() {}

    /** 每客户端 tick 调用一次(由 NeoForge ClientTickEvent.Post 驱动)。 */
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            camIdx = 0;
            camUuid = null;
            return;
        }

        // 按键上升沿(consumeClick 只在下按的那一 tick 返回 true)。
        if (KEY.consumeClick()) {
            cycle(mc);
        }

        // 相机目标失效(死亡/移除/换维度)自动弹回。
        if (camIdx != 0) {
            boolean ok = camUuid != null;
            Entity target = ok ? mc.level.getEntity(camUuid) : null;
            ok = ok && target != null && target.isAlive();
            if (!ok) {
                snapBack(mc, "同伴不在视野里了,已回到自己的视角");
            }
        }
    }

    /** 轮换:自己 → 客户端可见的同伴们(按 roster 顺序)→ 自己。 */
    private static void cycle(Minecraft mc) {
        List<Entity> candidates = new ArrayList<>();
        List<UUID> uuids = new ArrayList<>();
        candidates.add(mc.player);
        uuids.add(null);   // null = 自己
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            Entity e = mc.level.getEntity(entry.uuid());
            if (e != null && e.isAlive() && e != mc.player) {
                candidates.add(e);
                uuids.add(entry.uuid());
            }
        }

        int prev = camIdx;
        camIdx = (camIdx + 1) % candidates.size();
        camUuid = uuids.get(camIdx);
        if (camIdx == 0) {
            mc.setCameraEntity(null);   // 原版语义:null = 第一人称自己
            mc.gui.setOverlayMessage(Component.literal(
                    prev == 0 ? "同伴不在视野里(走近点再按 F8)" : "已回到自己的视角"), false);
        } else {
            Entity target = candidates.get(camIdx);
            mc.setCameraEntity(target);
            mc.gui.setOverlayMessage(Component.literal(
                    "已切换到 " + target.getName().getString() + " 的视角 · 再按 F8 切回 · F2 截图"), false);
        }
    }

    private static void snapBack(Minecraft mc, String why) {
        camIdx = 0;
        camUuid = null;
        mc.setCameraEntity(null);
        mc.gui.setOverlayMessage(Component.literal(why), false);
    }
}
