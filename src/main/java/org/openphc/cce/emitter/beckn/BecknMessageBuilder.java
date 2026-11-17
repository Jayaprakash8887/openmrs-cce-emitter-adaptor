package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;
import org.openphc.cce.emitter.config.EmitterProperties.BecknContractConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Builds the Beckn messages of the emitter (docs/beckn-integration.md § 8–9): {@code context},
 * {@code message.contract} and the {@code CceClinicalEvent} box, from the settings. Every message
 * carries exactly one performance entry, whose id is the event id. The commitment files are read
 * once, at start.
 */
@Component
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class BecknMessageBuilder {

    public static final String VERSION = "2.0.0";
    public static final String EVENT_TYPE = "cce:CceClinicalEvent";
    public static final String FHIR_VERSION = "4.0.1";
    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_ON_STATUS = "on_status";

    private final BecknConfig beckn;
    private final JsonNode hieCommitment;
    private final JsonNode referralsInCommitment;
    private final JsonNode referralsOutCommitment;

    public BecknMessageBuilder(EmitterProperties properties, ResourceLoader resourceLoader) {
        this.beckn = properties.getBeckn();
        this.hieCommitment = beckn.getLegs().isCceEvents()
                ? loadCommitment(resourceLoader, beckn.getOpenhim().getCommitmentFile(), "emitter.beckn.openhim.commitment-file")
                : null;
        this.referralsInCommitment = beckn.getLegs().isReferralsIn()
                ? loadCommitment(resourceLoader, beckn.getSpice().getReferralsIn().getCommitmentFile(),
                        "emitter.beckn.spice.referrals-in.commitment-file")
                : null;
        this.referralsOutCommitment = beckn.getLegs().isReferralsOut()
                ? loadCommitment(resourceLoader, beckn.getSpice().getReferralsOut().getCommitmentFile(),
                        "emitter.beckn.spice.referrals-out.commitment-file")
                : null;
    }

    /** {@code update} on the HIE feed contract, to {@code onix-bap}. */
    public OutgoingMessage hieUpdate(JsonNode resource, String eventId, String patientId) {
        var hie = beckn.getOpenhim();
        var self = beckn.getSelf();
        ObjectNode context = context(ACTION_UPDATE, self.getBapId(), self.getBapUri(),
                hie.getBppId(), hie.getBppUri(), hie.getTransactionId());
        ObjectNode performance = performance(eventId, null, resource, patientId);
        ObjectNode body = envelope(context, hie.getContractId(), false, hieCommitment, performance);
        return message(Leg.CCE_EVENTS, ACTION_UPDATE, beckn.getOnix().getBapCallerUrl(), body, eventId,
                resource, hie.getTransactionId(), hie.getContractId());
    }

    /** {@code on_status} on the "referrals in" contract (OpenMRS is the provider), to {@code onix-bpp}. */
    public OutgoingMessage peerOnStatus(JsonNode resource, String eventId, String patientId, String performanceStatus) {
        var peer = beckn.getSpice();
        var self = beckn.getSelf();
        BecknContractConfig contract = peer.getReferralsIn();
        ObjectNode context = context(ACTION_ON_STATUS, peer.getBapId(), peer.getBapUri(),
                self.getBppId(), self.getBppUri(), contract.getTransactionId());
        ObjectNode performance = performance(eventId, performanceStatus, resource, patientId);
        ObjectNode body = envelope(context, contract.getContractId(), true, referralsInCommitment, performance);
        return message(Leg.REFERRALS_IN, ACTION_ON_STATUS, beckn.getOnix().getBppCallerUrl(), body, eventId,
                resource, contract.getTransactionId(), contract.getContractId());
    }

    /** {@code update} on the "referrals out" contract (the peer is the provider), to {@code onix-bap}. */
    public OutgoingMessage peerReferralUpdate(JsonNode resource, String eventId, String patientId) {
        var peer = beckn.getSpice();
        var self = beckn.getSelf();
        BecknContractConfig contract = peer.getReferralsOut();
        ObjectNode context = context(ACTION_UPDATE, self.getBapId(), self.getBapUri(),
                peer.getBppId(), peer.getBppUri(), contract.getTransactionId());
        ObjectNode performance = performance(eventId, null, resource, patientId);
        ObjectNode body = envelope(context, contract.getContractId(), false, referralsOutCommitment, performance);
        return message(Leg.REFERRALS_OUT, ACTION_UPDATE, beckn.getOnix().getBapCallerUrl(), body, eventId,
                resource, contract.getTransactionId(), contract.getContractId());
    }

    private ObjectNode context(String action, String bapId, String bapUri, String bppId, String bppUri,
                               String transactionId) {
        ObjectNode context = BecknJson.MAPPER.createObjectNode();
        context.put("version", VERSION);
        context.put("action", action);
        String networkId = beckn.getSelf().getNetworkId();
        if (networkId != null && !networkId.isBlank()) {
            context.put("networkId", networkId);
        }
        context.put("bapId", bapId);
        context.put("bapUri", bapUri);
        context.put("bppId", bppId);
        context.put("bppUri", bppUri);
        context.put("transactionId", transactionId);
        // replaced on every attempt by OutgoingMessage.forAttempt()
        context.put("messageId", UUID.randomUUID().toString());
        context.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        return context;
    }

    private ObjectNode performance(String eventId, String status, JsonNode resource, String patientId) {
        ObjectNode performance = BecknJson.MAPPER.createObjectNode();
        performance.put("id", eventId);
        if (status != null) {
            performance.putObject("status").put("code", status);
        }
        ObjectNode attrs = performance.putObject("performanceAttributes");
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
        return performance;
    }

    private static ObjectNode envelope(ObjectNode context, String contractId, boolean withContractStatus,
                                       JsonNode commitment, ObjectNode performance) {
        ObjectNode root = BecknJson.MAPPER.createObjectNode();
        root.set("context", context);
        ObjectNode contract = root.putObject("message").putObject("contract");
        contract.put("id", contractId);
        if (withContractStatus) {
            contract.putObject("status").put("code", "ACTIVE");
        }
        contract.putArray("commitments").add(commitment.deepCopy());
        contract.putArray("performance").add(performance);
        return root;
    }

    private static OutgoingMessage message(Leg leg, String action, String callerUrl, ObjectNode body, String eventId,
                                           JsonNode resource, String transactionId, String contractId) {
        return new OutgoingMessage(leg, action, stripTrailingSlash(callerUrl) + "/" + action, body, eventId,
                BecknJson.text(resource, "resourceType"), transactionId, contractId);
    }

    static JsonNode loadCommitment(ResourceLoader loader, String location, String setting) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Beckn setting " + setting + " is not set");
        }
        Resource resource = loader.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            JsonNode node = BecknJson.MAPPER.readTree(in);
            if (node == null || !node.isObject() || BecknJson.text(node, "id") == null) {
                throw new IllegalStateException("Commitment file " + location + " (" + setting
                        + ") must hold a JSON object with an \"id\"");
            }
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read commitment file " + location + " (" + setting + "): "
                    + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
