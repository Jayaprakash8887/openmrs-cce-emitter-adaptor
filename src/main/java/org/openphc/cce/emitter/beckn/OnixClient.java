package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.RetryConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Posts one Beckn message to an {@code onix} caller and reads the answer
 * (docs/beckn-integration.md § 10). A 5xx, 408, 429, timeout or connection error is retried with
 * {@code emitter.openhim.retry.*} (linear backoff: backoff × attempt). Every attempt has a new
 * {@code messageId}; the performance id stays the same. Records the {@code openmrs.beckn.*} metrics.
 */
@Component
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class OnixClient {

    public static final String METRIC_SENT = "openmrs.beckn.messages.sent";
    public static final String METRIC_RETRIES = "openmrs.beckn.send.retries";
    public static final String METRIC_DROPPED = "openmrs.beckn.messages.dropped";
    public static final String METRIC_LATENCY = "openmrs.beckn.onix.latency";

    /** Final result of one message. {@code lastMessageId} is the {@code messageId} of the last attempt. */
    public record SendResult(OnixAnswer.Verdict verdict, int attempts, int httpStatus, String lastMessageId) {
        public boolean isAck() {
            return verdict == OnixAnswer.Verdict.ACK;
        }
    }

    private final RestTemplate onixRestTemplate;
    private final EmitterProperties properties;
    private final BecknMessageLog messageLog;
    private final MeterRegistry meterRegistry;

    public OnixClient(@Qualifier(BecknConfiguration.ONIX_REST_TEMPLATE) RestTemplate onixRestTemplate,
                      EmitterProperties properties,
                      BecknMessageLog messageLog,
                      MeterRegistry meterRegistry) {
        this.onixRestTemplate = onixRestTemplate;
        this.properties = properties;
        this.messageLog = messageLog;
        this.meterRegistry = meterRegistry;
    }

    public SendResult send(OutgoingMessage msg) {
        RetryConfig retry = properties.getOpenhim().getRetry();
        int maxAttempts = Math.max(1, retry.getMaxAttempts());
        long backoffMs = Math.max(0, retry.getBackoffMs());
        Tags tags = Tags.of("leg", msg.leg().tag(), "action", msg.action());

        for (int attempt = 1; ; attempt++) {
            ObjectNode body = msg.forAttempt();
            String messageId = body.path("context").path("messageId").asText();
            long start = System.nanoTime();
            OnixAnswer answer = post(msg.url(), body);
            long nanos = System.nanoTime() - start;
            meterRegistry.timer(METRIC_LATENCY, tags).record(nanos, TimeUnit.NANOSECONDS);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(nanos);

            OnixAnswer.Verdict verdict = answer.verdict();
            if (verdict == OnixAnswer.Verdict.ACK) {
                messageLog.finished(msg, messageId, attempt, answer, latencyMs, true);
                meterRegistry.counter(METRIC_SENT, tags.and("outcome", "ack")).increment();
                return new SendResult(verdict, attempt, answer.httpStatus(), messageId);
            }
            if (verdict == OnixAnswer.Verdict.NACK) {
                messageLog.finished(msg, messageId, attempt, answer, latencyMs, false);
                meterRegistry.counter(METRIC_SENT, tags.and("outcome", "nack")).increment();
                meterRegistry.counter(METRIC_DROPPED, tags.and("reason", "nack")).increment();
                return new SendResult(verdict, attempt, answer.httpStatus(), messageId);
            }
            if (attempt >= maxAttempts) {
                messageLog.finished(msg, messageId, attempt, answer, latencyMs, false);
                meterRegistry.counter(METRIC_SENT, tags.and("outcome", "error")).increment();
                meterRegistry.counter(METRIC_DROPPED, tags.and("reason", "retries-exhausted")).increment();
                return new SendResult(verdict, attempt, answer.httpStatus(), messageId);
            }
            messageLog.retry(msg, messageId, attempt, answer, latencyMs);
            meterRegistry.counter(METRIC_RETRIES, tags).increment();
            if (!sleep(backoffMs * attempt)) {
                meterRegistry.counter(METRIC_SENT, tags.and("outcome", "error")).increment();
                meterRegistry.counter(METRIC_DROPPED, tags.and("reason", "retries-exhausted")).increment();
                return new SendResult(verdict, attempt, answer.httpStatus(), messageId);
            }
        }
    }

    private OnixAnswer post(String url, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<String> response = onixRestTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(BecknJson.write(body), headers), String.class);
            return OnixAnswer.of(response.getStatusCode().value(), response.getBody());
        } catch (RestClientException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return OnixAnswer.noAnswer(root.getClass().getSimpleName() + ": " + root.getMessage());
        }
    }

    /** Returns false when interrupted (the poller is shutting down). */
    private static boolean sleep(long ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
