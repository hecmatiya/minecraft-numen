package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.ChatLines;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.client.voice.VoicePipeline;
import com.dwinovo.numen.network.payload.SpeechBubblePayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code speak} —— 外置大脑(MCP)模式下让小爱在游戏里说话的入口。
 *
 * <p>内置大脑的回复走 EntityAgentLoop 的"聊天框 + 头顶气泡 + 声线语音"三件套;
 * 而外置大脑只有工具,回复只回执给调用者,游戏里的小爱一声不吭。这个工具把
 * 同一条说话管线接出来给外部大脑用:
 *
 * <ul>
 *   <li>聊天框一行(近白正文,和内建回复同款格式);</li>
 *   <li>头顶气泡({@link SpeechBubblePayload KIND_TEXT});</li>
 *   <li>语音:若该同伴绑定了声线就真的说出来,没绑就只出文字。</li>
 * </ul>
 *
 * <p><b>纯客户端工具</b>:说话是客户端能力(聊天框/气泡/声线都在客户端进程),
 * 所以覆写 {@link #invoke} 直接在客户端执行,不走 {@code ServerToolTransport}
 * 的服务端身体路径——服务端上下文没有聊天框和声线库,走过去必炸。
 * 单刻完成、不占任务队列——说话不影响她在干什么(跟随/陪伴照跑)。
 *
 * <p>语音<b>复用同一个 {@link VoicePipeline}</b>(每同伴一个):连调多次时句子
 * 自然逐句排队,前句播完才起后句,不会叠音——"一句一句说"由语音自身时长
 * 决定节奏,而不是靠调用方掐秒。与内置大脑的 pipeline 相互独立,互不打断。
 */
public final class SpeakTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 每同伴一个说话管线(客户端会话级,常驻)。 */
    private static final Map<UUID, VoicePipeline> PIPES = new HashMap<>();

    /**
     * 每客户端 tick 驱动一次所有说话管线。语音播放的接续(上一句播完 → 起下一句)
     * 靠 {@link VoicePipeline#tick()} 推进;speak 派发时只触发开播,后续接续必须
     * 有人持续 tick——由 mod 的客户端 tick 钩子调用(服务端没有语音,不调)。
     */
    public static void tickAll() {
        for (VoicePipeline vp : PIPES.values()) {
            vp.tick();
        }
    }

    private record Args(String text, Boolean voice) {}

    @Override
    public String name() {
        return "speak";
    }

    @Override
    public String description() {
        return "Speak in the world, as yourself: a chat line + a speech bubble above your head, "
                + "spoken aloud with your bound voice if one is configured. Use this whenever you "
                + "want the owner to hear you say something in game — replies, warnings, banter, "
                + "a status update. Instant; does not interrupt what the body is doing.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("text", "What to say — one short line in the owner's language, spoken like you talk.")
                .optionalBool("voice", "Say it aloud with your voice (default true; falls back to text-only if no voice is bound).")
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
        if (a == null || a.text() == null || a.text().isBlank()) {
            call.complete(TaskResult.fail("text 必填:说点什么。").toJson());
            return;
        }
        String text = a.text().strip();
        boolean wantVoice = a.voice() == null || a.voice();
        UUID uuid = call.ctx().entityUuid();
        if (uuid == null) {
            call.complete(TaskResult.fail("companion 不在当前加载范围,说不了话。").toJson());
            return;
        }
        try {
            String name = NumenRoster.instance().name(uuid);
            if (name == null) {
                name = "Numen";
            }

            // 聊天框一行(定格行,同内建回复格式)。
            ChatLines.companion(name, text);

            // 头顶气泡:客户端 → 服务端,服务端校验主人后转发给附近玩家(含主人)。
            String capped = text.length() > SpeechBubblePayload.MAX_TEXT / 2 - 4
                    ? text.substring(0, SpeechBubblePayload.MAX_TEXT / 2 - 4)
                    : text;
            Services.NETWORK.sendToServer(new SpeechBubblePayload(
                    uuid, SpeechBubblePayload.KIND_TEXT, capped));

            // 语音:绑定了声线才说。每同伴复用一个 pipeline——连续调 speak 时
            // 句子自然排队,前句播完才起后句,不叠音(节奏由语音时长决定)。
            if (wantVoice) {
                VoiceLibrary.Entry cfg = VoiceLibrary.instance().resolve(uuid);
                if (cfg != null) {
                    VoicePipeline vp = PIPES.get(uuid);
                    if (vp == null) {
                        vp = new VoicePipeline(uuid);
                        vp.beginTurn(cfg);   // 首次:绑定声线(打断的是自己的空队列)
                        PIPES.put(uuid, vp);
                    }
                    vp.speak(text);
                }
            }
            call.complete(TaskResult.ok("说完了。" + (wantVoice ? "" : "(文字模式)"),
                    Map.of("spoken", true)).toJson());
        } catch (Throwable t) {
            call.complete(TaskResult.fail("speak 失败: " + t).toJson());
        }
    }

}
