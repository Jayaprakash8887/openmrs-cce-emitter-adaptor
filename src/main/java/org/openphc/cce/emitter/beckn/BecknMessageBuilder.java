package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Builds the Sygnal message of the emitter, {@code sygnal/event/report} to the HIE
 * (docs/beckn-integration.md § 8–9), from the settings: {@code context} and one
 * {@code sygnal:CoordinationEvent} whose {@code eventAttributes} is the {@code CceClinicalEvent} box.
 * The event id is the {@code CoordinationEvent} id. There is no contract and no commitment.
 */
@Component
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class BecknMessageBuilder {

    public static final String VERSION = "2.0.0";
    public static final String ACTION_EVENT_REPORT = "sygnal/event/report";
    public static final String COORDINATION_EVENT_TYPE = "sygnal:CoordinationEvent";
    public static final String SYGNAL_EVENT_TYPE = "DELIVERY_RECORDED";
    public static final String GRANULARITY = "INDIVIDUAL";
    public static final String ROLE = "PROVIDER";
    public static final String EVENT_TYPE = "cce:CceClinicalEvent";
    public static final String FHIR_VERSION = "4.0.1";

    private final BecknConfig beckn;

    public BecknMessageBuilder(EmitterProperties properties) {
        this.beckn = properties.getBeckn();
    }

    /** {@code sygnal/event/report} to the HIE, posted to this emitter's onix Sygnal caller. */
    public OutgoingMessage eventReport(JsonNode resource, String eventId, String patientId) {
        var self = beckn.getSelf();
        var hie = beckn.getHie();
        String transactionId = isBlank(hie.getTransactionId())
                ? UUID.randomUUID().toString()
                : hie.getTransactionId().trim();

        ObjectNode root = BecknJson.MAPPER.createObjectNode();
        ObjectNode context = root.putObject("context");
        context.put("version", VERSION);
        context.put("action", ACTION_EVENT_REPORT);
        context.put("networkId", self.getNetworkId());
        context.put("senderId", self.getId());
        context.put("receiverId", hie.getId());
        context.put("receiverUri", hie.getUri());
        context.put("transactionId", transactionId);
        // replaced on every attempt by OutgoingMessage.forAttempt()
        context.put("messageId", UUID.randomUUID().toString());
        context.put("timestamp", now());

        ObjectNode event = root.putObject("message").putArray("events").addObject();
        event.put("@type", COORDINATION_EVENT_TYPE);
        event.put("id", eventId);
        event.put("revision", 1);
        event.put("eventType", SYGNAL_EVENT_TYPE);
        event.put("granularity", GRANULARITY);
        ObjectNode from = event.putObject("from");
        from.put("id", self.getId());
        from.put("role", ROLE);
        event.put("occurredAt", occurredAt(resource));
        event.set("eventAttributes", clinicalEvent(resource, patientId));

        String url = stripTrailingSlash(beckn.getOnix().getCallerUrl()) + "/" + ACTION_EVENT_REPORT;
        return new OutgoingMessage(Leg.CCE_EVENTS, ACTION_EVENT_REPORT, url, root, eventId,
                BecknJson.text(resource, "resourceType"), transactionId);
    }

    /** The {@code cce:CceClinicalEvent} box, unchanged from the Beckn {@code performanceAttributes}. */
    private ObjectNode clinicalEvent(JsonNode resource, String patientId) {
        ObjectNode attrs = BecknJson.MAPPER.createObjectNode();
        attrs.put("@context", stripTrailingSlash(beckn.getSchemaBaseUrl()) + "/CceClinicalEvent/v1.0/context.jsonld");
        attrs.put("@type", EVENT_TYPE);
        String resourceType = BecknJson.text(resource, "resourceType");
        attrs.put("eventType", resourceType);
        attrs.put("patientId", patientId);
        attrs.put("fhirVersion", FHIR_VERSION);
        String id = BecknJson.text(resource, "id");
        if (resourceType != null && id != null) {
            attrs.put("sourceEventId", resourceType + "/" + id);
        }
        attrs.set("resource", resource.deepCopy());
        return attrs;
    }

    /**
     * When the event happened: {@code meta.lastUpdated} of the resource, i.e. when OpenMRS recorded
     * this version of it (the version the event id is the hash of), as a UTC instant. Falls back to
     * the time the message is built when it is missing or not a full timestamp. The time of the
     * clinical act itself (for example {@code Encounter.period.start}) stays in the resource; CCE
     * reads it from there for its schedules.
     */
    static String occurredAt(JsonNode resource) {
        String lastUpdated = BecknJson.text(resource == null ? null : resource.path("meta"), "lastUpdated");
        if (lastUpdated != null) {
            try {
                return OffsetDateTime.parse(lastUpdated).toInstant().toString();
            } catch (DateTimeParseException e) {
                // not a full FHIR instant: fall back to now
            }
        }
        return now();
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
