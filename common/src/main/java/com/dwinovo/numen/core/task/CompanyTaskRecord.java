package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

/**
 * 「陪着」——比 follow 松散的常驻模式:远了跟过去,近了在身边走动闲逛,
 * 社交回应照常(不打断)。同样是常驻任务,期限用 {@link Long#MAX_VALUE}
 * (与 {@link FollowTaskRecord} 同一约定,超时判定 gameTime >= deadline
 * 永远不会到)。
 */
public final class CompanyTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "company";

    /** 跟谁。{@code null} = 主人。 */
    public final Integer entityId;
    /** 那一只的 UUID(身份看这个,id 只是查找键)。 */
    public final java.util.UUID targetUuid;

    public CompanyTaskRecord(String toolCallId, Integer entityId, java.util.UUID targetUuid) {
        super(TOOL_NAME, toolCallId, Long.MAX_VALUE);
        markStanding();   // 常驻任务:新活会顶掉它,活干完由状态机自动恢复
        this.entityId = entityId;
        this.targetUuid = targetUuid;
    }

    @Override
    public String describe() {
        String who = entityId == null ? "你" : "实体 " + entityId;
        return "陪在" + who + "身边";
    }
}
