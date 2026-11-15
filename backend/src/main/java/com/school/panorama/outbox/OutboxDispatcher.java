package com.school.panorama.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 事务外异步投递器：DB 轮询 + JDK HttpClient 轻量 HTTP，不引入消息集群（D6）。
 *
 * <p>关键边界：本类不在任何 Controller / Service 调用链内发 HTTP，
 * 业务响应在事务提交后立即返回。领取事件用一个**短事务**
 * （FOR UPDATE SKIP LOCKED + 标记 IN_FLIGHT），HTTP 投递与 ACK/退避都在事务之外。
 * 底座停机、超时、5xx 只影响 outbox_events 状态，绝不导致业务事务失败或阻塞。</p>
 *
 * <p>只有收到 {@code {ack:true}}（底座已持久化）才置 DELIVERED；
 * 失败 attempts+1 + 指数退避，超阈值置 DEAD，可由 /api/admin/retry 重放。</p>
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final HttpClient httpClient;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "outbox-dispatcher");
        thread.setDaemon(true);
        return thread;
    });

    private final String eventUrl;
    private final long pollMs;
    private final long timeoutMs;
    private final long backoffBaseMs;
    private final int batch;
    private final int maxAttempts;

    public OutboxDispatcher(OutboxMapper outboxMapper, ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager,
                            @Value("${ontology.target-url:http://ontology-service:8080}") String targetUrl,
                            @Value("${ontology.dispatch.poll-ms:1000}") long pollMs,
                            @Value("${ontology.dispatch.batch:50}") int batch,
                            @Value("${ontology.dispatch.max-attempts:8}") int maxAttempts,
                            @Value("${ontology.dispatch.backoff-base-ms:1000}") long backoffBaseMs,
                            @Value("${ontology.dispatch.timeout-ms:3000}") long timeoutMs) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
        this.eventUrl = targetUrl.replaceAll("/+$", "") + "/internal/events";
        this.pollMs = Math.max(pollMs, 200L);
        this.timeoutMs = Math.max(timeoutMs, 200L);
        this.backoffBaseMs = Math.max(backoffBaseMs, 100L);
        this.batch = Math.max(batch, 1);
        this.maxAttempts = Math.max(maxAttempts, 1);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    /** 首轮延后 3 秒，避开启动迁移器尚未建表的窗口；异常一律吞在投递线程。 */
    @PostConstruct
    public void start() {
        executor.scheduleWithFixedDelay(this::pollSafely, 3000L, pollMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private void pollSafely() {
        try {
            poll();
        } catch (Exception e) {
            log.warn("Outbox 轮询跳过一轮（不影响业务）：{}", e.getMessage());
        }
    }

    private void poll() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        List<OutboxEvent> events = template.execute(status -> {
            List<OutboxEvent> claimed = outboxMapper.claim(batch);
            for (OutboxEvent event : claimed) {
                outboxMapper.markInFlight(event.eventId);
            }
            return claimed;
        });
        if (events == null || events.isEmpty()) {
            return;
        }
        for (OutboxEvent event : events) {
            // 逐条投递，单条失败不影响同批其它事件，也不影响业务。
            deliver(event);
        }
    }

    private void deliver(OutboxEvent event) {
        try {
            String body = objectMapper.writeValueAsString(toEventJson(event));
            HttpRequest request = HttpRequest.newBuilder(URI.create(eventUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200 && acknowledged(response.body())) {
                outboxMapper.markDelivered(event.eventId);
            } else {
                fail(event, "HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
            }
        } catch (Exception e) {
            fail(event, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void fail(OutboxEvent event, String error) {
        try {
            int attempts = event.attempts + 1;
            boolean dead = attempts >= maxAttempts;
            long backoff = Math.min(backoffBaseMs * (1L << Math.min(attempts - 1, 6)), MAX_BACKOFF_MS);
            LocalDateTime nextRetry = LocalDateTime.now().plusNanos(backoff * 1_000_000L);
            outboxMapper.markFailed(event.eventId, attempts, dead ? null : nextRetry,
                    abbreviate(error), dead);
            if (dead) {
                log.warn("Outbox 事件重试耗尽转 DEAD，可在 /api/admin/retry 重放：{}", event.eventId);
            }
        } catch (Exception ignored) {
            // 状态回写失败也不能影响业务；下一轮会重新领取该事件（去重表保证幂等）。
        }
    }

    /** 构造 03 契约 §3 的完整事件 JSON；payload 必须还原成 JSON 对象而非字符串。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toEventJson(OutboxEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.eventId);
        body.put("batchId", event.batchId);
        body.put("aggregateType", event.aggregateType);
        body.put("aggregateId", event.aggregateId);
        body.put("changeKind", event.changeKind);
        body.put("revision", event.revision);
        body.put("businessTime", event.businessTime);
        body.put("occurredAt", event.occurredAt);
        try {
            body.put("payload", event.payload == null ? Map.of()
                    : objectMapper.readValue(event.payload, Object.class));
        } catch (Exception e) {
            body.put("payload", Map.of());
        }
        return body;
    }

    private boolean acknowledged(String responseBody) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(responseBody,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return Boolean.TRUE.equals(parsed.get("ack"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
