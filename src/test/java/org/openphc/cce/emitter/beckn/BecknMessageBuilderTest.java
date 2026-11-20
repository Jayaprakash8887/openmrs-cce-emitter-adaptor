package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Message shape against the sample in docs/beckn-integration.md § 9 and the CCE Sygnal contract. */
class BecknMessageBuilderTest {

    private static final String ENCOUNTER = "{\"resourceType\":\"Encounter\",\"id\":\"e\","
            + "\"subject\":{\"reference\":\"Patient/p\"}}";

    private static JsonNode sample() throws Exception {
        try (InputStream in = BecknMessageBuilderTest.class.getResourceAsStream("/beckn/sample-event-report.json")) {
            return BecknJson.MAPPER.readTree(in);
        }
    }

    private static BecknMessageBuilder builder(EmitterProperties properties) {
        return new BecknMessageBuilder(properties);
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
        JsonNode resource = sample.at("/message/events/0/eventAttributes/resource");
        String eventId = sample.at("/message/events/0/id").asText();

        OutgoingMessage msg = builder(BecknTestSettings.properties()).eventReport(resource, eventId, "1198503150001234");

        assertEquals(Leg.CCE_EVENTS, msg.leg());
        assertEquals("sygnal/event/report", msg.action());
        assertEquals("http://onix-openmrs:8082/sygnal/caller/sygnal/event/report", msg.url());
        assertEquals(BecknTestSettings.TRANSACTION_ID, msg.transactionId());
        assertEquals(sample, normalise(msg.forAttempt(), sample));
    }

    @Test
    void contextIsTheClosedSygnalContextAndTheEventHasNoContractOrContext() {
        ObjectNode body = builder(BecknTestSettings.properties())
                .eventReport(BecknJson.parse(ENCOUNTER), "ev", "p").forAttempt();

        assertEquals(Set.of("context", "message"), fieldNames(body));
        assertEquals(new TreeSet<>(List.of("version", "action", "networkId", "senderId", "receiverId", "receiverUri",
                "transactionId", "messageId", "timestamp")), fieldNames(body.get("context")));
        assertEquals(Set.of("events"), fieldNames(body.get("message")));
        assertEquals(1, body.at("/message/events").size());
        JsonNode event = body.at("/message/events/0");
        assertEquals(new TreeSet<>(List.of("@type", "id", "revision", "eventType", "granularity", "from", "occurredAt",
                "eventAttributes")), fieldNames(event));
        assertFalse(event.has("@context"));
        assertEquals(1, event.get("revision").asInt());
        assertTrue(event.get("revision").isInt());
        assertEquals(body.at("/context/senderId"), event.at("/from/id"));
        assertEquals("PROVIDER", event.at("/from/role").asText());
        String text = BecknJson.write(body);
        for (String gone : new String[]{"bapId", "bppId", "bapUri", "bppUri", "contract", "commitments", "performance"}) {
            assertFalse(text.contains("\"" + gone + "\""), gone);
        }
    }

    @Test
    void networkIdAndSchemaBaseUrlAndCallerUrlComeFromSettings() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSelf().setNetworkId("openphc-demo");
        properties.getBeckn().setSchemaBaseUrl("https://schemas.example.org/base/");
        properties.getBeckn().getOnix().setCallerUrl("http://onix:9000/sygnal/caller/");
        OutgoingMessage msg = builder(properties).eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");
        ObjectNode body = msg.forAttempt();

        assertEquals("http://onix:9000/sygnal/caller/sygnal/event/report", msg.url());
        assertEquals("openphc-demo", body.at("/context/networkId").asText());
        assertEquals("https://schemas.example.org/base/CceClinicalEvent/v1.0/context.jsonld",
                body.at("/message/events/0/eventAttributes/@context").asText());
    }

    @Test
    void withoutFixedTransactionIdEachEventGetsItsOwnUuidKeptAcrossAttempts() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getHie().setTransactionId("");
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
}
