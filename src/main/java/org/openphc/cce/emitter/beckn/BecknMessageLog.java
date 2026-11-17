package org.openphc.cce.emitter.beckn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One log line per Beckn message when it ends (INFO, or WARN when it failed), and one WARN line
 * per retry (docs/beckn-integration.md § 13.2). Ids and results only: no message body, no patient
 * id, and no resource id (a Patient's id is its national ID).
 */
@Component
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class BecknMessageLog {

    private static final Logger log = LoggerFactory.getLogger(BecknMessageLog.class);

    public void finished(OutgoingMessage msg, String messageId, int attempt, OnixAnswer answer, long latencyMs,
                         boolean success) {
        String line = line(msg, messageId, attempt, answer, latencyMs);
        if (success) {
            log.info(line);
        } else {
            log.warn(line);
        }
    }

    public void retry(OutgoingMessage msg, String messageId, int attempt, OnixAnswer answer, long latencyMs) {
        log.warn(line(msg, messageId, attempt, answer, latencyMs) + " next=retry");
    }

    static String line(OutgoingMessage msg, String messageId, int attempt, OnixAnswer answer, long latencyMs) {
        StringBuilder sb = new StringBuilder("beckn")
                .append(" action=").append(msg.action())
                .append(" leg=").append(msg.leg().tag())
                .append(" messageId=").append(messageId)
                .append(" transactionId=").append(msg.transactionId())
                .append(" contractId=").append(msg.contractId())
                .append(" eventId=").append(msg.eventId())
                .append(" resourceType=").append(msg.resourceType())
                .append(" attempt=").append(attempt)
                .append(" result=").append(answer.result());
        if (answer.httpStatus() > 0) sb.append(" httpStatus=").append(answer.httpStatus());
        if (answer.errorCode() != null) sb.append(" errorCode=").append(answer.errorCode());
        if (answer.errorMessage() != null) sb.append(" errorMessage=\"").append(oneLine(answer.errorMessage())).append('"');
        if (answer.failure() != null) sb.append(" failure=\"").append(oneLine(answer.failure())).append('"');
        sb.append(" latencyMs=").append(latencyMs);
        return sb.toString();
    }

    private static String oneLine(String s) {
        String flat = s.replaceAll("[\\r\\n]+", " ").replace('"', '\'');
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
