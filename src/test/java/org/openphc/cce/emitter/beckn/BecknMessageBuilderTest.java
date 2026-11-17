package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Message shapes against the samples in docs/beckn-integration.md § 9. */
class BecknMessageBuilderTest {

    private static JsonNode sample(String name) throws Exception {
        try (InputStream in = BecknMessageBuilderTest.class.getResourceAsStream("/beckn/" + name)) {
            return BecknJson.MAPPER.readTree(in);
        }
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

    @Test
    void hieUpdateMatchesSample91() throws Exception {
        JsonNode sample = sample("sample-hie-update.json");
        JsonNode resource = sample.at("/message/contract/performance/0/performanceAttributes/resource");
        String eventId = sample.at("/message/contract/performance/0/id").asText();

        OutgoingMessage msg = builder(BecknTestSettings.properties()).hieUpdate(resource, eventId, "1198503150001234");

        assertEquals(Leg.CCE_EVENTS, msg.leg());
        assertEquals("update", msg.action());
        assertEquals("http://onix-bap:8081/bap/caller/update", msg.url());
        assertEquals(sample, normalise(msg.forAttempt(), sample));
    }

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
        ObjectNode body = builder(BecknTestSettings.properties()).peerReferralUpdate(resource, "ev-1", "119").forAttempt();

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
    void networkIdAndSchemaBaseUrlComeFromSettings() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSelf().setNetworkId("openphc-demo");
        properties.getBeckn().setSchemaBaseUrl("https://schemas.example.org/base/");
        ObjectNode body = builder(properties)
                .hieUpdate(BecknJson.parse("{\"resourceType\":\"Encounter\",\"id\":\"e\"}"), "ev", "p").forAttempt();

        assertEquals("openphc-demo", body.at("/context/networkId").asText());
        assertEquals("https://schemas.example.org/base/CceClinicalEvent/v1.0/context.jsonld",
                body.at("/message/contract/performance/0/performanceAttributes/@context").asText());
    }

    @Test
    void networkIdLeftOutWhenNotSet() {
        ObjectNode body = builder(BecknTestSettings.properties())
                .hieUpdate(BecknJson.parse("{\"resourceType\":\"Encounter\",\"id\":\"e\"}"), "ev", "p").forAttempt();
        assertFalse(body.get("context").has("networkId"));
    }

    @Test
    void eachAttemptHasNewMessageIdAndSameEventId() {
        OutgoingMessage msg = builder(BecknTestSettings.properties())
                .hieUpdate(BecknJson.parse("{\"resourceType\":\"Encounter\",\"id\":\"e\"}"), "ev", "p");
        ObjectNode first = msg.forAttempt();
        ObjectNode second = msg.forAttempt();
        assertNotEquals(first.at("/context/messageId"), second.at("/context/messageId"));
        assertEquals("ev", first.at("/message/contract/performance/0/id").asText());
        assertEquals("ev", second.at("/message/contract/performance/0/id").asText());
    }

    @Test
    void missingCommitmentFileFailsAtStart() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getOpenhim().setCommitmentFile("classpath:beckn/does-not-exist.json");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> builder(properties));
        assertTrue(e.getMessage().contains("emitter.beckn.openhim.commitment-file"));
    }
}
