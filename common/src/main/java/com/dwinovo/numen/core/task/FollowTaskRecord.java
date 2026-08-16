package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

/**
 * 「跟着」——她当前在做的事就是跟着某个东西走。默认是主人,给了 {@link #entityId}
 * 就是跟着那一只。
 *
 * <p>没有"干完"这回事,所以期限用 {@link Long#MAX_VALUE}——期限回答的是
 * "该多久干完",而这件活的终点只有主人换掉它。(1.21.1 时代它有独立的
 * {@code NO_DEADLINE} 常量,26.1.2 重构后期限就是裸 long,用最大值表达"永不"。
 * 超时判定是 {@code gameTime >= deadline},MAX_VALUE 永远不会到。)
 *
 * <p>{@code keepWithin} 是跟到多近就算到位。到位之后任务<b>休眠</b>(而不是结束):
 * 身体让给别人,目标一走远它自己就醒过来。这跟原版 {@code Goal.canUse()} 是同一
 * 个道理——休眠不是失败。
 */
public final class FollowTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "follow";

    /** 跟到这么近就算到位(米)。 */
    public final double keepWithin;

    /**
     * 跟着谁。{@code null} = 主人。
     *
     * <p>这两种目标<b>消失的含义不一样</b>,所以任务里分两支:主人下线是暂时的,他会
     * 回来,那时该休眠等着;点名的实体死了或者被卸载就是没了,再等也不会回来,该收尾
     * 报给模型。
     */
    public final Integer entityId;

    /**
     * 那一只的 UUID。<b>身份看这个,{@link #entityId} 只是查找键。</b>
     *
     * <p>常驻任务会跨重启重放(存的是当时那次调用的 args),而运行期 id 每次开服重新发,
     * 只认 id 的话重放之后她可能一声不吭地跟上另一只完全不相干的东西。UUID 是稳的,
     * 对不上就是目标没了。
     */
    public final java.util.UUID targetUuid;

    public FollowTaskRecord(String toolCallId, double keepWithin, Integer entityId,
                            java.util.UUID targetUuid) {
        super(TOOL_NAME, toolCallId, Long.MAX_VALUE);
        markStanding();   // 常驻任务:新活会顶掉它(1.21.1 的"mine 顶掉 follow"语义)
        this.keepWithin = keepWithin;
        this.entityId = entityId;
        this.targetUuid = targetUuid;
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        String who = entityId == null ? "你" : "实体 " + entityId;
        return "跟着" + who + ",保持 " + (int) keepWithin + " 米";
    }
}
