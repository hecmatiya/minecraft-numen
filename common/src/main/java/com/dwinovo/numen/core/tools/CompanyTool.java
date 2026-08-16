package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.CompanyTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 陪着——follow 的松散版常驻工具:远了跟过去,近了在你身边走动闲逛,
 * 社交回应照常(不打断)。常驻任务:没有"干完"这回事,只有被主人换掉。
 *
 * <p>不给 {@code entity_id} 就是陪主人。给了就跟那一只。
 */
public final class CompanyTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(Integer entity_id, String entity_uuid) {}

    @Override
    public String name() {
        return CompanyTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Keep the owner (or a named entity) company — a LOOSER standing job than follow: "
                + "when they are far, walk over and keep up; when they are close, wander around "
                + "beside them (pacing, circling), looking at them from time to time, and still "
                + "answer their gestures (wave, crouch, gift…) without dropping the job. "
                + "Use it when the owner wants you around but not glued to them — hanging out, "
                + "working nearby, guarding casually. Ends only when given something else to do.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("entity_id",
                        "Who to keep company with, by runtime entity id from scan_nearby_entities. "
                                + "Leave it out for your owner.",
                        1, Integer.MAX_VALUE)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        Integer entityId = parsed == null ? null : parsed.entity_id();
        java.util.UUID targetUuid = null;
        if (entityId != null) {
            var target = ((net.minecraft.server.level.ServerLevel) companion.level())
                    .getEntity(entityId);
            if (target == null || target.isRemoved() || target == companion) {
                reply.accept("no entity with id " + entityId
                        + " is here — scan_nearby_entities first, ids do not survive restarts");
                return;
            }
            // 身份钉进 args:常驻任务跨重启是重放这次调用,id 每次开服重发。
            targetUuid = target.getUUID();
            args.addProperty("entity_uuid", targetUuid.toString());
        } else if (parsed != null && parsed.entity_uuid() != null) {
            targetUuid = java.util.UUID.fromString(parsed.entity_uuid());
        }
        dispatchAsync(companion,
                new CompanyTaskRecord(toolCallId, entityId, targetUuid), reply);
    }
}
