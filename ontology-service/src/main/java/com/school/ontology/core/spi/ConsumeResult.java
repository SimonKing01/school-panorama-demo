package com.school.ontology.core.spi;

/** 消费结果（ACK）：只有本体库单事务提交成功才返回 persisted=true。 */
public record ConsumeResult(boolean ack, String eventId, boolean persisted,
                            String skipped, String error, long revision) {

    public static ConsumeResult persisted(String eventId, long revision) {
        return new ConsumeResult(true, eventId, true, null, null, revision);
    }

    public static ConsumeResult skipped(String eventId, String reason, long revision) {
        return new ConsumeResult(true, eventId, false, reason, null, revision);
    }

    public static ConsumeResult failure(String eventId, String error) {
        return new ConsumeResult(false, eventId, false, null, error, 0L);
    }
}
