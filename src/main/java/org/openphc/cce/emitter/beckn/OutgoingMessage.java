package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One Sygnal message ready to post. {@link #forAttempt()} gives the body for one attempt: a copy
 * with a new {@code context.messageId} and {@code context.timestamp}; the event id
 * ({@code message.events[0].id}) and the {@code transactionId} never change.
 */
public record OutgoingMessage(Leg leg, String action, String url, ObjectNode body,
                              String eventId, String resourceType, String transactionId) {

    public ObjectNode forAttempt() {
        ObjectNode copy = body.deepCopy();
        ObjectNode context = (ObjectNode) copy.get("context");
        context.put("messageId", UUID.randomUUID().toString());
        context.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        return copy;
    }
}
