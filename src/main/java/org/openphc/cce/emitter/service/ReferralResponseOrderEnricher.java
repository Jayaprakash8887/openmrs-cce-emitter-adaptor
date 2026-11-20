package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.OpenmrsConfig;
import org.openphc.cce.emitter.config.EmitterProperties.ReferralResponseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enriches any DomainResource emitted from a Referral Response form submission with the
 * source referral order details (instructions, urgency, accession number, concept) as a
 * FHIR extension.
 *
 * <p>The link between the form submission and the order is the DISCONTINUE order that
 * OpenMRS creates in the same encounter when the referral order is closed. The DISCONTINUE
 * order's {@code previousOrder} references the original referral order with all its details.
 *
 * <p>For Encounter resources: checks if encounter type matches the configured Referral Response
 * type → queries OpenMRS REST for orders in that encounter → finds the DISCONTINUE order →
 * fetches previousOrder details → adds extension with order metadata.
 *
 * <p>The DISCONTINUE order is created a moment after the form is saved, so the poll can see the
 * encounter (and the orders saved with it) before it exists. The referral is then the patient's one
 * inbound referral order ("Referral In" encounter) that was open when the encounter was saved. A
 * Referral Response whose referral cannot be identified yet is not cached, so later resources of the
 * same encounter (a lab result entered afterwards, say) are still linked.
 *
 * <p>For any other DomainResource (Observation, Condition, Procedure, etc.): resolves the
 * encounter reference via {@code getEncounter()} → same enrichment flow.
 */
@Service
@RequiredArgsConstructor
public class ReferralResponseOrderEnricher {

    private static final Logger log = LoggerFactory.getLogger(ReferralResponseOrderEnricher.class);
    private static final String METRIC_PREFIX = "openmrs.emitter.referral.response.enrichment";

    private static final String EXTENSION_URL = "http://cce.openphc.org/StructureDefinition/source-referral-order";
    private static final String REFERRAL_ORDER_TYPE_UUID = "778a9dc6-87d9-49c1-83d9-caa01041a8ad";

    private static final DateTimeFormatter OPENMRS_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private static final long CACHE_TTL_SECONDS = 3600L;
    private static final int CACHE_MAX_SIZE = 10_000;

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** Cache: encounterUuid → OrderDetails (null means "no order found, skip") */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Enriches the resource with source referral order details if it was produced by a
     * Referral Response form submission. Works with any DomainResource — Encounter,
     * Observation, Condition, Procedure, DiagnosticReport, etc.
     * No-op for non-DomainResource or resources without an encounter link.
     */
    public void enrich(IBaseResource resource) {
        if (resource == null) return;
        if (!(resource instanceof DomainResource domainResource)) return;
        ReferralResponseConfig cfg = properties.getReferral().getResponse();
        if (cfg == null || !cfg.isEnabled()) return;

        String encounterId = resolveEncounterId(domainResource);
        if (encounterId == null) return;

        OrderDetails details = resolveOrderDetails(encounterId);
        if (details == null) return;

        applyExtension(domainResource, details);
        log.info("[ReferralResponseOrderEnricher] ENRICHED {}/{} with order={}",
                resource.fhirType(), domainResource.getIdElement().getIdPart(), details.orderUuid());
    }

    /**
     * Resolves the encounter UUID from a resource.
     * - If the resource IS an Encounter: validates encounter type and returns its own ID.
     * - Otherwise: reflectively calls getEncounter() to obtain the encounter reference.
     */
    private String resolveEncounterId(DomainResource resource) {
        if (resource instanceof Encounter encounter) {
            String encounterTypeUuid = extractEncounterTypeUuid(encounter);
            String cfgEncType = properties.getReferral().getResponse().getResponseEncounterTypeUuid();
            if (cfgEncType == null || !cfgEncType.equals(encounterTypeUuid)) return null;
            String id = encounter.getIdElement().getIdPart();
            return (id != null && !id.isBlank()) ? id : null;
        }

        // Generic path: look for getEncounter() returning a Reference
        Reference encounterRef = extractEncounterReference(resource);
        return extractIdFromReference(encounterRef);
    }

    /**
     * Reflectively extracts the encounter Reference from any DomainResource that has
     * a getEncounter() method (Observation, Condition, Procedure, DiagnosticReport, etc.).
     */
    private Reference extractEncounterReference(DomainResource resource) {
        try {
            Method method = resource.getClass().getMethod("getEncounter");
            Object result = method.invoke(resource);
            if (result instanceof Reference ref) return ref;
        } catch (NoSuchMethodException e) {
            // Resource type doesn't have getEncounter() — not applicable
        } catch (Exception e) {
            log.debug("[ReferralResponseOrderEnricher] Could not extract encounter ref from {}: {}",
                    resource.fhirType(), e.getMessage());
        }
        return null;
    }

    /**
     * Resolves order details for a given encounter UUID. Uses cache.
     * Returns null if the encounter isn't a Referral Response or doesn't have a DISCONTINUE referral order.
     * Single REST call: checks encounter type + finds DISCONTINUE order + gets previousOrder details.
     */
    private OrderDetails resolveOrderDetails(String encounterUuid) {
        evictIfOverCapacity();
        Instant now = Instant.now();
        CacheEntry cached = cache.get(encounterUuid);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            recordOutcome("cache-hit");
            return cached.details();
        }

        try {
            Lookup lookup = fetchEncounterOrderDetails(encounterUuid);
            OrderDetails details = lookup.details();
            if (details == null) {
                if (lookup.referralResponse()) {
                    // A Referral Response whose referral is not identifiable yet. Not cached: the
                    // referral may become identifiable (its DISCONTINUE order is created moments after
                    // the form is saved), and later resources of the encounter must still be linked.
                    log.info("[ReferralResponseOrderEnricher] Referral Response encounter {} has no identifiable referral order yet",
                            encounterUuid);
                    recordOutcome("no-order-yet");
                    return null;
                }
                cache.put(encounterUuid, new CacheEntry(null, now.plus(Duration.ofSeconds(CACHE_TTL_SECONDS))));
                recordOutcome("no-order");
                return null;
            }

            log.info("[ReferralResponseOrderEnricher] Found source order {} for encounter {}", details.orderUuid(), encounterUuid);
            cache.put(encounterUuid, new CacheEntry(details, now.plus(Duration.ofSeconds(CACHE_TTL_SECONDS))));
            recordOutcome("hit");
            return details;

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("[ReferralResponseOrderEnricher] Encounter {} not found via REST", encounterUuid);
            recordOutcome("miss");
            cache.put(encounterUuid, new CacheEntry(null, now.plus(Duration.ofSeconds(CACHE_TTL_SECONDS))));
            return null;
        } catch (ResourceAccessException | HttpClientErrorException e) {
            log.warn("[ReferralResponseOrderEnricher] REST error for encounter {}: {}", encounterUuid, e.getMessage());
            recordOutcome("error");
            return null;
        } catch (Exception e) {
            log.warn("[ReferralResponseOrderEnricher] Unexpected error for encounter {}: {}", encounterUuid, e.getMessage());
            recordOutcome("error");
            return null;
        }
    }

    /**
     * Checks if the encounter's type UUID matches the configured Referral Response encounter type,
     * and finds the referral order it answers:
     * <ol>
     *   <li>the referral order closed by a DISCONTINUE order in the encounter (the referral-fulfillment
     *       ESM creates it right after the form is saved), else</li>
     *   <li>the patient's one inbound referral order that was open when the encounter was saved —
     *       the case when the encounter is polled before that DISCONTINUE order exists.</li>
     * </ol>
     */
    private Lookup fetchEncounterOrderDetails(String encounterUuid) throws Exception {
        OpenmrsConfig openmrs = properties.getOpenmrs();
        String url = stripTrailingSlash(openmrs.getBaseUrl())
                + "/ws/rest/v1/encounter/" + encounterUuid
                + "?v=custom:(uuid,encounterDatetime,patient:(uuid),encounterType:(uuid),orders:(uuid,action,orderType:(uuid),previousOrder:(uuid,accessionNumber,instructions,urgency,concept:(uuid,display),orderType:(uuid,display))))";

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(buildHeaders()), String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) return Lookup.NOT_A_RESPONSE;

        JsonNode root = objectMapper.readTree(body);

        // Check encounter type
        JsonNode etNode = root.get("encounterType");
        if (etNode == null) return Lookup.NOT_A_RESPONSE;
        String etUuid = textOrNull(etNode, "uuid");
        String cfgUuid = properties.getReferral().getResponse().getResponseEncounterTypeUuid();
        if (cfgUuid == null || !cfgUuid.equals(etUuid)) return Lookup.NOT_A_RESPONSE;

        OrderDetails discontinued = discontinuedReferral(root.get("orders"));
        if (discontinued != null) {
            return new Lookup(true, discontinued);
        }
        JsonNode patient = root.get("patient");
        return new Lookup(true, openInboundReferral(
                patient == null ? null : textOrNull(patient, "uuid"),
                parseOpenmrsDate(textOrNull(root, "encounterDatetime"))));
    }

    /** The referral order closed by a DISCONTINUE referral order among {@code orders}, or null. */
    private OrderDetails discontinuedReferral(JsonNode orders) {
        if (orders == null || !orders.isArray() || orders.isEmpty()) return null;

        for (JsonNode order : orders) {
            String action = textOrNull(order, "action");
            if (!"DISCONTINUE".equals(action)) continue;

            JsonNode orderTypeNode = order.get("orderType");
            if (orderTypeNode == null) continue;
            String otUuid = textOrNull(orderTypeNode, "uuid");
            if (!REFERRAL_ORDER_TYPE_UUID.equals(otUuid)) continue;

            JsonNode prev = order.get("previousOrder");
            if (prev == null || prev.isNull()) continue;

            OrderDetails details = orderDetails(prev);
            if (details != null) return details;
        }
        return null;
    }

    /**
     * The patient's inbound referral order that was open at {@code at}: a NEW referral order in a
     * "Referral In" encounter, activated by then and not stopped before. Null when there is none, or
     * more than one (the response cannot be told apart from the others).
     */
    private OrderDetails openInboundReferral(String patientUuid, OffsetDateTime at) throws Exception {
        String inboundEncounterType = properties.getReferral().getDirection().getInboundEncounterTypeUuid();
        if (patientUuid == null || at == null || inboundEncounterType == null) return null;

        String url = stripTrailingSlash(properties.getOpenmrs().getBaseUrl())
                + "/ws/rest/v1/order?patient=" + patientUuid
                + "&orderTypes=" + REFERRAL_ORDER_TYPE_UUID
                + "&v=custom:(uuid,action,dateActivated,dateStopped,accessionNumber,instructions,urgency,"
                + "concept:(uuid,display),orderType:(uuid,display),encounter:(encounterType:(uuid)))";
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(buildHeaders()), String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) return null;

        List<JsonNode> open = new ArrayList<>();
        for (JsonNode order : objectMapper.readTree(body).path("results")) {
            if (!"NEW".equals(textOrNull(order, "action"))) continue;
            if (!inboundEncounterType.equals(textOrNull(order.path("encounter").path("encounterType"), "uuid"))) continue;
            OffsetDateTime activated = parseOpenmrsDate(textOrNull(order, "dateActivated"));
            OffsetDateTime stopped = parseOpenmrsDate(textOrNull(order, "dateStopped"));
            if (activated == null || activated.isAfter(at)) continue;
            if (stopped != null && stopped.isBefore(at)) continue;
            open.add(order);
        }
        if (open.size() != 1) {
            log.info("[ReferralResponseOrderEnricher] {} open inbound referral orders for patient {} at {}; none linked",
                    open.size(), patientUuid, at);
            return null;
        }
        return orderDetails(open.get(0));
    }

    /** The extension's fields from an OpenMRS REST order, or null when it has no uuid. */
    private OrderDetails orderDetails(JsonNode order) {
        String uuid = textOrNull(order, "uuid");
        if (uuid == null) return null;
        JsonNode conceptNode = order.get("concept");
        JsonNode orderTypeNode = order.get("orderType");
        return new OrderDetails(uuid,
                textOrNull(order, "accessionNumber"),
                textOrNull(order, "instructions"),
                textOrNull(order, "urgency"),
                conceptNode != null ? textOrNull(conceptNode, "uuid") : null,
                conceptNode != null ? textOrNull(conceptNode, "display") : null,
                orderTypeNode != null ? textOrNull(orderTypeNode, "display") : null);
    }

    /** OpenMRS REST datetime ({@code 2026-11-19T13:58:44.000+0000}), or null. */
    static OffsetDateTime parseOpenmrsDate(String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value, OPENMRS_DATETIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Applies the source referral order extension to any DomainResource.
     */
    private void applyExtension(DomainResource resource, OrderDetails details) {
        Extension ext = new Extension(EXTENSION_URL);

        // Sub-extension: order UUID
        ext.addExtension(new Extension("orderUuid",
                new org.hl7.fhir.r4.model.StringType(details.orderUuid())));

        // Sub-extension: accession number
        if (details.accessionNumber() != null) {
            ext.addExtension(new Extension("accessionNumber",
                    new org.hl7.fhir.r4.model.StringType(details.accessionNumber())));
        }

        // Sub-extension: instructions as CodeableConcept
        if (details.instructions() != null) {
            ReferralResponseConfig cfg = properties.getReferral().getResponse();
            CodeableConcept instructionsCc = new CodeableConcept()
                    .addCoding(new Coding()
                            .setSystem(cfg.getInstructionsSystem())
                            .setCode(cfg.getInstructionsCode())
                            .setDisplay(cfg.getInstructionsDisplay()))
                    .setText(details.instructions());
            ext.addExtension(new Extension("instructions", instructionsCc));
        }

        // Sub-extension: urgency
        if (details.urgency() != null) {
            ext.addExtension(new Extension("urgency",
                    new org.hl7.fhir.r4.model.StringType(details.urgency())));
        }

        // Sub-extension: concept (what was referred)
        if (details.conceptUuid() != null) {
            CodeableConcept conceptCc = new CodeableConcept()
                    .addCoding(new Coding()
                            .setSystem("urn:openmrs:concept")
                            .setCode(details.conceptUuid())
                            .setDisplay(details.conceptDisplay()));
            ext.addExtension(new Extension("concept", conceptCc));
        }

        // Sub-extension: order type
        if (details.orderTypeDisplay() != null) {
            ext.addExtension(new Extension("orderType",
                    new org.hl7.fhir.r4.model.StringType(details.orderTypeDisplay())));
        }

        // Add to resource (idempotent — remove existing if present)
        resource.getExtension().removeIf(e -> EXTENSION_URL.equals(e.getUrl()));
        resource.addExtension(ext);
    }

    private String extractEncounterTypeUuid(Encounter encounter) {
        // The FHIR Encounter from OpenMRS has type[] with coding referencing the encounter type UUID
        for (CodeableConcept cc : encounter.getType()) {
            for (Coding coding : cc.getCoding()) {
                // OpenMRS FHIR2 module uses the encounter type UUID as the code
                if (coding.getCode() != null) return coding.getCode();
            }
        }
        return null;
    }

    private String extractIdFromReference(Reference ref) {
        if (ref == null) return null;
        String refStr = ref.getReference();
        if (refStr == null) return null;
        // "Encounter/uuid" -> "uuid"
        if (refStr.contains("/")) {
            return refStr.substring(refStr.lastIndexOf('/') + 1);
        }
        return refStr;
    }

    private HttpHeaders buildHeaders() {
        OpenmrsConfig openmrs = properties.getOpenmrs();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String authHeader = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (authHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return headers;
    }

    private void evictIfOverCapacity() {
        if (cache.size() >= CACHE_MAX_SIZE) {
            cache.clear();
        }
    }

    private void recordOutcome(String outcome) {
        meterRegistry.counter(METRIC_PREFIX, Tags.of("outcome", outcome)).increment();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record OrderDetails(String orderUuid, String accessionNumber, String instructions,
                                String urgency, String conceptUuid, String conceptDisplay,
                                String orderTypeDisplay) {}

    private record CacheEntry(OrderDetails details, Instant expiresAt) {}

    /** Whether the encounter is a Referral Response, and the referral order it answers (null when unknown). */
    private record Lookup(boolean referralResponse, OrderDetails details) {
        static final Lookup NOT_A_RESPONSE = new Lookup(false, null);
    }
}
