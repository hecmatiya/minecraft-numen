package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.core.debug.DebugCommands;
import com.dwinovo.numen.core.debug.PathDebugRenderer;
import com.dwinovo.numen.core.pathing.cache.PathCaches;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.ScanBlocksJob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

/**
 * NeoForge entry point for the numen-core tool pack. Registers the tools and
 * task runners into the numen-api engine, then wires the server-tick work its
 * tools need (budget-sliced block scans, the off-thread pathfinder's chunk
 * snapshots). The engine itself is brought up by the separate numen-api mod,
 * which core depends on.
 */
@Mod(Constants.MOD_ID)
public class NumenCoreNeoForge {

    public NumenCoreNeoForge(IEventBus eventBus, ModContainer container) {
        NumenCore.init();

        // 社交性格配置:config/numen/social.json ({"<uuid>": "tsun"|"gentle"|"default"})
        com.dwinovo.numen.core.social.SocialReactions.loadConfig(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                        .resolve("numen").resolve("social.json"));

        NeoForge.EVENT_BUS.addListener(NumenCoreNeoForge::onServerTickPost);
        // 玩家动作 → 社交信号(事件式):右键点同伴 = 送礼/搭话;攻击同伴 = 冒犯。
        // MC 原版对玩家实体右键没有任何效果,所以"送礼"由本 mod 实现:
        // 手持物品右键她 = 物品转移进她的背包(满了掉她脚边),一次一件;
        // 空手右键 = 搭话/摸头。两种都会触发 GIFT 社交信号。
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract e) -> {
            if (e.getEntity().level().isClientSide()) return;   // 客户端预测事件,服务端才是权威
            if (e.getTarget() instanceof com.dwinovo.numen.entity.NumenPlayer companion) {
                net.minecraft.server.level.ServerPlayer owner = (net.minecraft.server.level.ServerPlayer) e.getEntity();
                net.minecraft.world.item.ItemStack hand = owner.getMainHandItem();
                if (!hand.isEmpty()) {
                    net.minecraft.world.item.ItemStack give = hand.copy();
                    give.setCount(1);
                    hand.shrink(1);
                    if (!companion.getInventory().add(give) && !give.isEmpty()) {
                        companion.drop(give, true);   // 背包满了,剩余掉她脚边
                    }
                    Constants.LOG.info("[numen-core] {} gave 1x {} to companion {}",
                            owner.getName().getString(),
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(give.getItem()).getPath(),
                            companion.getUUID());
                }
                com.dwinovo.numen.core.social.SocialSignals.record(companion.getUUID(),
                        com.dwinovo.numen.core.social.SocialSignals.Kind.GIFT,
                        companion.level().getGameTime());
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.AttackEntityEvent e) -> {
            if (e.getEntity().level().isClientSide()) return;   // 客户端预测事件,服务端才是权威
            if (e.getTarget() instanceof com.dwinovo.numen.entity.NumenPlayer companion) {
                // 打她:记到被打的同伴头上
                com.dwinovo.numen.core.social.SocialSignals.record(companion.getUUID(),
                        com.dwinovo.numen.core.social.SocialSignals.Kind.ATTACK,
                        companion.level().getGameTime());
                return;
            }
            // 打别的怪:记到"附近同伴"头上(简化:全在线同伴都感知到"主人打怪")
            for (var p : e.getEntity().level().getServer().getPlayerList().getPlayers()) {
                if (p instanceof com.dwinovo.numen.entity.NumenPlayer companion) {
                    if (companion.distanceToSqr(e.getEntity()) <= 12 * 12) {
                        com.dwinovo.numen.core.social.SocialSignals.record(companion.getUUID(),
                                com.dwinovo.numen.core.social.SocialSignals.Kind.ATTACK_OTHER,
                                companion.level().getGameTime());
                    }
                }
            }
        });
        // 捡东西:附近同伴感知到"主人捡了东西"(Post = 捡起完成)。
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post e) -> {
            if (!(e.getPlayer() instanceof net.minecraft.server.level.ServerPlayer owner)
                    || owner.level().isClientSide()) {
                return;   // 客户端预测事件,服务端才是权威
            }
            for (var p : owner.level().getServer().getPlayerList().getPlayers()) {
                if (p instanceof com.dwinovo.numen.entity.NumenPlayer companion
                        && companion.distanceToSqr(owner) <= 12 * 12) {
                    com.dwinovo.numen.core.social.SocialSignals.record(companion.getUUID(),
                            com.dwinovo.numen.core.social.SocialSignals.Kind.COLLECT_ITEM,
                            companion.level().getGameTime());
                }
            }
        });
        // 右键方块:主手拿着方块 = 放置,否则 = 使用方块(开箱子/炉子等)。
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) -> {
            if (e.getEntity().level().isClientSide()) return;   // 客户端预测事件,服务端才是权威
            var kind = e.getItemStack().getItem() instanceof net.minecraft.world.item.BlockItem
                    ? com.dwinovo.numen.core.social.SocialSignals.Kind.PLACING
                    : com.dwinovo.numen.core.social.SocialSignals.Kind.INTERACTING;
            for (var p : e.getLevel().getServer().getPlayerList().getPlayers()) {
                if (p instanceof com.dwinovo.numen.entity.NumenPlayer companion
                        && companion.distanceToSqr(e.getEntity()) <= 12 * 12) {
                    com.dwinovo.numen.core.social.SocialSignals.record(companion.getUUID(), kind,
                            companion.level().getGameTime());
                }
            }
        });
        // 丢东西:物品实体出现在世界里(玩家 Q 键丢出)。
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) -> {
            if (e.getLevel().isClientSide()) return;   // 客户端预测事件,服务端才是权威
            if (!(e.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item)
                    || item.getOwner() == null
                    || !(item.getOwner() instanceof net.minecraft.server.level.ServerPlayer owner)) {
                return;
            }
            for (var p : e.getLevel().getServer().getPlayerList().getPlayers()) {
                if (p instanceof com.dwinovo.numen.entity.NumenPlayer companion
                        && companion.distanceToSqr(owner) <= 12 * 12) {
                    com.dwinovo.numen.core.social.SocialSignals.record(companion.getUUID(),
                            com.dwinovo.numen.core.social.SocialSignals.Kind.DROP_ITEM,
                            companion.level().getGameTime());
                }
            }
        });
        // Release pathfinding chunk-ref snapshots when the server stops (don't pin an old world).
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> PathCaches.dropAll());
        // Drop the shared target-block index with the world it describes.
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> com.dwinovo.numen.core.scan.TargetIndex.dropAll());
        // Debug verbs merged into the /numen root registered by the engine mod.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                DebugCommands.register(e.getDispatcher()));

        // 游戏内用例的登记。1.21.5 起 gametest 不再有注解入口,用例要主动注册成
        // test_instance 条目;注册本身只在开了 gametest 的开发环境里生效(引擎侧
        // RegisterGameTestsEvent 自己把关),正式环境不会有任何动作。
        com.dwinovo.numen.core.gametest.NumenGameTests.register(eventBus);

        // Client-only: declare core's built-in skills, read in place from the
        // skills/ dir bundled in this jar. Skills feed the client-side LLM, so
        // this never runs on a dedicated server.
        if (FMLLoader.getCurrent().getDist() == Dist.CLIENT) {
            declareBundledSkills();
            // speak 工具的声音管线:播放接续靠每客户端 tick 推进(上一句播完自动起下一句)。
            // 服务端没有语音,不注册(客户端事件类只在客户端触碰)。
            NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                            com.dwinovo.numen.core.tools.SpeakTool.tickAll());
        }

        Constants.LOG.info("numen-core initialised on NeoForge.");
    }

    private static void declareBundledSkills() {
        // Resolve the jar-internal skills/ dir via the classloader (loader-agnostic and stable
        // across MC versions, unlike NeoForge's shifting IModFile API): the resource URL maps to a
        // Path on the mod's (union) filesystem, which the engine reads in place.
        try {
            java.net.URL url = NumenCoreNeoForge.class.getResource("/skills");
            if (url != null) {
                SkillRegistry.instance().declareBundled(Path.of(url.toURI()));
                return;
            }
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-core] failed to resolve bundled skills/: {}", ex.toString());
        }
        Constants.LOG.warn("[numen-core] no bundled skills/ dir found in jar");
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        // 排程机器的心跳随机器归了 numen-api;core 只 tick 自己的工具配套。
        ScanBlocksJob.tick(event.getServer());
        PathCaches.serverTick(event.getServer());
        // Periodic eviction sweep for the target-block index (entries of unloaded chunks).
        com.dwinovo.numen.core.scan.TargetIndex.serverTick(event.getServer());
        // Debug particles for pathing state, sent only to players with debug on.
        PathDebugRenderer.serverTick(event.getServer());
        // set_timer 的表到点扫描(秒级降频在 TimerRegistry 内部)。
        com.dwinovo.numen.task.TimerRegistry.tick(event.getServer());
        // 玩家动作姿态采样(挥臂/蹲下/视线),喂给社交反射链。
        com.dwinovo.numen.core.social.SocialSignals.detectTick(event.getServer());
        // 空闲模式自动恢复:陪伴模式工作做完自动回到陪伴。
        com.dwinovo.numen.core.social.CompanionModes.tickAutoRestore(event.getServer());
    }
}
