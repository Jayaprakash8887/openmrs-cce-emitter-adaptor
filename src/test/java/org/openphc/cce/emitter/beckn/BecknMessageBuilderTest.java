package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Message shapes against the samples in docs/beckn-integration.md § 9: the Sygnal event report (CCE
 * Sygnal contract, with a flat {@code cce:CceClinicalEvent} v2.0 as the event) and the Beckn referral
 * messages to Spice (unchanged from demo-beckn).
 */
class BecknMessageBuilderTest {

    private static final String ENCOUNTER = "{\"resourceType\":\"Encounter\",\"id\":\"e\","
            + "\"subject\":{\"reference\":\"Patient/p\"}}";

    private static JsonNode sample(String name) throws Exception {
        try (InputStream in = BecknMessageBuilderTest.class.getResourceAsStream("/beckn/" + name)) {
            return BecknJson.MAPPER.readTree(in);
        }
    }

    private static JsonNode sample() throws Exception {
        return sample("sample-event-report.json");
    }

    private static BecknMessageBuilder builder(EmitterProperties properties) {
        return new BecknMessageBuilder(properties, new DefaultResourceLoader());
    }

    /** Copies the per-attempt fields (messageId, timestamp) from the sample, after checking their form. */
    private static ObjectNode normalise(ObjectNode built, JsonNode sample) {
        ObjectNode context = (ObjectNode) built.get("context");
        UUID.fromString(context.get("messageId").asText());
        Instant.parse(context.get("timestamp").asText());
        context.set("messageId", sample.path("context").get("messageId"));
        context.set("timestamp", sample.path("context").get("timestamp"));
        return built;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void eventReportMatchesSample() throws Exception {
        JsonNode sample = sample();
        JsonNode resource = sample.at("/message/events/0/resource");
        String eventId = sample.at("/message/events/0/id").asText();

        OutgoingMessage msg = builder(BecknTestSettings.properties()).eventReport(resource, eventId, "1198503150001234");

        assertEquals(Leg.CCE_EVENTS, msg.leg());
        assertEquals("sygnal/event/report", msg.action());
        assertEquals("http://onix-openmrs:8082/sygnal/caller/sygnal/event/report", msg.url());
        assertEquals(BecknTestSettings.TRANSACTION_ID, msg.transactionId());
        assertEquals(sample, normalise(msg.forAttempt(), sample));
    }

    @Test
    void contextIsTheClosedSygnalContextAndTheEventIsAFlatClosedCceClinicalEvent() {
        ObjectNode body = builder(BecknTestSettings.properties())
                .eventReport(BecknJson.parse(ENCOUNTER), "ev", "p").forAttempt();

        assertEquals(Set.of("context", "message"), fieldNames(body));
        assertEquals(new TreeSet<>(List.of("version", "action", "networkId", "senderId", "receiverId", "receiverUri",
                "transactionId", "messageId", "timestamp")), fieldNames(body.get("context")));
        assertEquals(Set.of("events"), fieldNames(body.get("message")));
        assertEquals(1, body.at("/message/events").size());
        JsonNode event = body.at("/message/events/0");
        // the event is closed: exactly these keys, none of them null
        assertEquals(new TreeSet<>(List.of("@context", "@type", "id", "occurredAt", "patientId", "fhirVersion",
                "sourceEventId", "resource")), fieldNames(event));
        event.fields().forEachRemaining(field -> assertFalse(field.getValue().isNull(), field.getKey()));
        assertEquals("https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v2.0/context.jsonld",
                event.get("@context").asText());
        assertEquals("cce:CceClinicalEvent", event.get("@type").asText());
        assertEquals("ev", event.get("id").asText());
        Instant.parse(event.get("occurredAt").asText());
        assertEquals("p", event.get("patientId").asText());
        assertEquals("4.0.1", event.get("fhirVersion").asText());
        assertEquals("Encounter/e", event.get("sourceEventId").asText());
        assertEquals(BecknJson.parse(ENCOUNTER), event.get("resource"));
        String text = BecknJson.write(body);
        for (String gone : new String[]{"bapId", "bppId", "bapUri", "bppUri", "contract", "commitments", "performance",
                "eventAttributes", "performanceAttributes", "revision", "eventType", "granularity", "from"}) {
            assertFalse(text.contains("\"" + gone + "\""), gone);
        }
        assertFalse(text.contains("sygnal:"), "no Sygnal type in the message");
        assertFalse(text.contains("CoordinationEvent"), "no Sygnal type in the message");
        assertFalse(text.contains("DELIVERY_RECORDED"), "no Sygnal event type in the message");
    }

    @Test
    void sourceEventIdIsLeftOutNotNullWhenTheResourceHasNoId() {
        JsonNode event = builder(BecknTestSettings.properties())
                .eventReport(BecknJson.parse("{\"resourceType\":\"Encounter\",\"subject\":{\"reference\":\"Patient/p\"}}"),
                        "ev", "p")
                .forAttempt().at("/message/events/0");

        assertEquals(new TreeSet<>(List.of("@context", "@type", "id", "occurredAt", "patientId", "fhirVersion",
                "resource")), fieldNames(event));
    }

    @Test
    void networkIdAndSchemaBaseUrlAndCallerUrlComeFromSettings() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSygnal().setNetworkId("openphc-demo");
        properties.getBeckn().setSchemaBaseUrl("https://schemas.example.org/base/");
        properties.getBeckn().getSygnal().setCallerUrl("http://onix:9000/sygnal/caller/");
        OutgoingMessage msg = builder(properties).eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");
        ObjectNode body = msg.forAttempt();

        assertEquals("http://onix:9000/sygnal/caller/sygnal/event/report", msg.url());
        assertEquals("openphc-demo", body.at("/context/networkId").asText());
        assertEquals("https://schemas.example.org/base/CceClinicalEvent/v2.0/context.jsonld",
                body.at("/message/events/0/@context").asText());
    }

    @Test
    void withoutFixedTransactionIdEachEventGetsItsOwnUuidKeptAcrossAttempts() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSygnal().getHie().setTransactionId("");
        BecknMessageBuilder builder = builder(properties);

        OutgoingMessage first = builder.eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");
        OutgoingMessage second = builder.eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");

        String tx = first.forAttempt().at("/context/transactionId").asText();
        UUID.fromString(tx);
        assertEquals(tx, first.forAttempt().at("/context/transactionId").asText());
        assertEquals(tx, first.transactionId());
        assertNotEquals(tx, second.forAttempt().at("/context/transactionId").asText());
    }

    @Test
    void eachAttemptHasNewMessageIdAndSameEventId() {
        OutgoingMessage msg = builder(BecknTestSettings.properties())
                .eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");
        ObjectNode first = msg.forAttempt();
        ObjectNode second = msg.forAttempt();
        assertNotEquals(first.at("/context/messageId"), second.at("/context/messageId"));
        assertEquals("ev", first.at("/message/events/0/id").asText());
        assertEquals("ev", second.at("/message/events/0/id").asText());
        assertEquals(first.at("/message/events/0/occurredAt"), second.at("/message/events/0/occurredAt"));
    }

    @Test
    void occurredAtIsLastUpdatedAsUtcInstant() {
        JsonNode resource = BecknJson.parse("{\"resourceType\":\"Observation\",\"id\":\"o\","
                + "\"meta\":{\"lastUpdated\":\"2026-11-13T10:00:12.345+02:00\"}}");
        assertEquals("2026-11-13T08:00:12.345Z", BecknMessageBuilder.occurredAt(resource));
    }

    @Test
    void occurredAtFallsBackToNowWithoutAFullLastUpdated() {
        List<JsonNode> resources = new ArrayList<>();
        resources.add(BecknJson.parse(ENCOUNTER));
        resources.add(BecknJson.parse("{\"resourceType\":\"Encounter\",\"meta\":{\"lastUpdated\":\"2026-11-13\"}}"));
        for (JsonNode resource : resources) {
            Instant occurredAt = Instant.parse(BecknMessageBuilder.occurredAt(resource));
            assertTrue(Duration.between(occurredAt, Instant.now()).abs().toSeconds() < 60, occurredAt.toString());
        }
    }

    // ---- Spice referral legs (standard Beckn, as on demo-beckn) ----

    @Test
    void onStatusMatchesSample92() throws Exception {
        JsonNode sample = sample("sample-on-status.json");
        JsonNode resource = sample.at("/message/contract/performance/0/performanceAttributes/resource");
        String eventId = sample.at("/message/contract/performance/0/id").asText();

        OutgoingMessage msg = builder(BecknTestSettings.properties())
                .peerOnStatus(resource, eventId, "1198503150001234", "COMPLETED");

        assertEquals(Leg.REFERRALS_IN, msg.leg());
        assertEquals("http://onix-bpp:8082/bpp/caller/on_status", msg.url());
        assertEquals(sample, normalise(msg.forAttempt(), sample));
    }

    @Test
    void referralOutUpdateUsesReferralsOutContract() throws Exception {
        JsonNode resource = BecknJson.parse("{\"resourceType\":\"ServiceRequest\",\"id\":\"sr9\",\"status\":\"active\","
                + "\"subject\":{\"reference\":\"Patient/119\"}}");
        OutgoingMessage msg = builder(BecknTestSettings.properties()).peerReferralUpdate(resource, "ev-1", "119");
        ObjectNode body = msg.forAttempt();

        assertEquals(Leg.REFERRALS_OUT, msg.leg());
        assertEquals("http://onix-bap:8081/bap/caller/update", msg.url());
        assertEquals("update", body.at("/context/action").asText());
        assertEquals("bap.openmrs.example.org", body.at("/context/bapId").asText());
        assertEquals("bpp.spice.example.org", body.at("/context/bppId").asText());
        assertEquals("https://spice.example.org/bpp/receiver", body.at("/context/bppUri").asText());
        assertEquals("b52a7c3d-0e4f-4b8c-9d2e-3f4a5b6c7d8e", body.at("/context/transactionId").asText());
        assertEquals("7c3d9e1f-5a2b-4d6c-8e7f-0a1b2c3d4e5f", body.at("/message/contract/id").asText());
        assertEquals("referral", body.at("/message/contract/commitments/0/id").asText());
        assertEquals(1, body.at("/message/contract/performance").size());
        assertEquals("ev-1", body.at("/message/contract/performance/0/id").asText());
        assertEquals("ServiceRequest/sr9", body.at("/message/contract/performance/0/performanceAttributes/sourceEventId").asText());
        assertEquals(resource, body.at("/message/contract/performance/0/performanceAttributes/resource"));
    }

    @Test
    void referralNetworkIdIsSelfNetworkIdAndLeftOutWhenNotSet() {
        JsonNode resource = BecknJson.parse(ENCOUNTER);
        EmitterProperties properties = BecknTestSettings.properties();
        assertFalse(builder(properties).peerReferralUpdate(resource, "ev", "p").forAttempt().get("context").has("networkId"));

        properties.getBeckn().getSelf().setNetworkId("openphc-demo");
        assertEquals("openphc-demo",
                builder(properties).peerReferralUpdate(resource, "ev", "p").forAttempt().at("/context/networkId").asText());
    }

    @Test
    void missingCommitmentFileFailsAtStart() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSpice().getReferralsIn().setCommitmentFile("classpath:beckn/does-not-exist.json");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> builder(properties));
        assertTrue(e.getMessage().contains("emitter.beckn.spice.referrals-in.commitment-file"));
    }
}
