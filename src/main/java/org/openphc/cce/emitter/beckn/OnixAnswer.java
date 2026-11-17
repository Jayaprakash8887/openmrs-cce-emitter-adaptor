package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What {@code onix} answered to one attempt (docs/beckn-integration.md § 10). The adapter passes
 * the far side's {@code Ack} / {@code Nack} back in the same call:
 * {@code {"message":{"status":"ACK"|"NACK","messageId":…,"error":{"code":…,"message":…}}}}.
 *
 * <ul>
 *   <li>2xx and not {@code NACK} → {@link Verdict#ACK};</li>
 *   <li>{@code NACK} in the body, or a 4xx other than 408 / 429 → {@link Verdict#NACK} (no retry);</li>
 *   <li>5xx, 408, 429, timeout or no answer → {@link Verdict#RETRY}.</li>
 * </ul>
 */
public record OnixAnswer(int httpStatus, String status, String errorCode, String errorMessage, String failure) {

    public enum Verdict { ACK, NACK, RETRY }

    /** No HTTP answer at all: timeout, connection refused, unknown host. */
    public static OnixAnswer noAnswer(String failure) {
        return new OnixAnswer(0, null, null, null, failure == null ? "no answer" : failure);
    }

    /** Reads an HTTP answer. The body may be empty or not JSON. */
    public static OnixAnswer of(int httpStatus, String body) {
        String status = null;
        String code = null;
        String message = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = BecknJson.MAPPER.readTree(body);
                JsonNode msg = root.path("message");
                status = BecknJson.text(msg, "status");
                if (status == null) {
                    // Beckn 1.x adapters answer {"message":{"ack":{"status":"ACK"}}}
                    status = BecknJson.text(msg.path("ack"), "status");
                }
                JsonNode error = msg.has("error") ? msg.path("error") : root.path("error");
                code = BecknJson.text(error, "code");
                message = BecknJson.text(error, "message");
            } catch (Exception e) {
                // not JSON: decide by HTTP status alone
            }
        }
        return new OnixAnswer(httpStatus, status, code, message, null);
    }

    public Verdict verdict() {
        if (failure != null) return Verdict.RETRY;
        boolean nackBody = "NACK".equalsIgnoreCase(status);
        if (httpStatus >= 200 && httpStatus < 300) {
            return nackBody ? Verdict.NACK : Verdict.ACK;
        }
        if (httpStatus == 408 || httpStatus == 429 || httpStatus >= 500) return Verdict.RETRY;
        return Verdict.NACK;
    }

    /** The {@code result=} value of the log line. */
    public String result() {
        if (failure != null) return "NO_ANSWER";
        return status != null ? status.toUpperCase() : "HTTP_" + httpStatus;
    }
}
