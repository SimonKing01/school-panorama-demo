package com.school.panorama.outbox;

import com.school.panorama.common.ApiException;
import com.school.panorama.common.Resp;
import com.school.panorama.service.DemoClockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 业务侧 Outbox 运维端点（本轮新增，不改任何既有端点）：
 * 查看待处理/失败/死信事件与状态计数，并手动重放 DEAD 事件。
 *
 * <p>只操作业务库自己的 outbox_events，不调用本体服务；本体服务停机对本端点无影响。</p>
 *
 * <p>两个前缀并存：{@code /api/admin} 与业务既有 /api 风格一致；
 * {@code /admin} 是主理人裁决 4 指定的「底座代理转发」目标地址
 * （{@code POST http://backend:8080/admin/retry?eventId=...}），
 * 底座只读业务库，重放必须由业务侧回写 outbox_events.status。</p>
 */
@RestController
@RequestMapping({"/api/admin", "/admin"})
public class OutboxAdminController {

    private final OutboxMapper outboxMapper;
    private final DemoClockService demoClock;

    public OutboxAdminController(OutboxMapper outboxMapper, DemoClockService demoClock) {
        this.outboxMapper = outboxMapper;
        this.demoClock = demoClock;
    }

    @GetMapping("/outbox")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        int size = Math.min(Math.max(limit, 1), 200);
        int from = Math.max(offset, 0);
        java.util.List<Map<String, Object>> rows = outboxMapper.list(status, size, from);
        return Resp.map("demoTime", demoClock.demoTimeString(),
                "statusCounts", outboxMapper.statusCounts(),
                "total", rows.size(),
                "items", rows);
    }

    /** 重放 DEAD / FAILED 事件：重新排队，由投递器按原有 revision 顺序重投。 */
    @PostMapping("/retry")
    public Map<String, Object> retry(@RequestParam String eventId) {
        OutboxEvent event = outboxMapper.findById(eventId);
        if (event == null) {
            throw new ApiException(404, "NOT_FOUND", "Outbox 事件不存在：" + eventId);
        }
        int changed = outboxMapper.requeue(eventId);
        return Resp.map("demoTime", demoClock.demoTimeString(),
                "eventId", eventId,
                "status", changed > 0 ? "PENDING" : event.status,
                "attempts", changed > 0 ? 0 : event.attempts,
                "message", changed > 0 ? "已重新排队" : "仅 DEAD/FAILED 事件可重放，当前状态未改变");
    }
}
