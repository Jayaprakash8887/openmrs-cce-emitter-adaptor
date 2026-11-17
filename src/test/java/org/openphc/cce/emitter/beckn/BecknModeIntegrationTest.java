package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.service.FhirPollerService;
import org.openphc.cce.emitter.service.ForwardResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Beckn mode end to end against a stubbed onix (and a stubbed OpenMRS for the poller test):
 * Ack, Nack, 500-then-Ack retry with a new messageId and the same performance id, referrals.
 */
@SpringBootTest(properties = {
        "emitter.target=beckn",
        "emitter.polling.fhir.enabled=false",
        "emitter.patient.national-id.enabled=false",
        "emitter.openhim.retry.max-attempts=3",
        "emitter.openhim.retry.backoff-ms=1",
        "emitter.beckn.onix.timeout=2s",
        "emitter.beckn.self.bap-id=bap.openmrs.example.org",
        "emitter.beckn.self.bap-uri=https://openmrs.example.org/bap/receiver",
        "emitter.beckn.self.bpp-id=bpp.openmrs.example.org",
        "emitter.beckn.self.bpp-uri=https://openmrs.example.org/bpp/receiver",
        "emitter.beckn.openhim.bpp-id=bpp.openhim.example.org",
        "emitter.beckn.openhim.bpp-uri=https://hie.example.org/bpp/receiver",
        "emitter.beckn.openhim.contract-id=5b1e7f3c-2a4d-4c6e-8f90-a1b2c3d4e5f6",
        "emitter.beckn.openhim.transaction-id=0d5f8c1a-7b2e-4f6d-9a3c-1e2b3c4d5e6f",
        "emitter.beckn.spice.bap-id=bap.spice.example.org",
        "emitter.beckn.spice.bap-uri=https://spice.example.org/bap/receiver",
        "emitter.beckn.spice.bpp-id=bpp.spice.example.org",
        "emitter.beckn.spice.bpp-uri=https://spice.example.org/bpp/receiver",
        "emitter.beckn.spice.facility-codes=0417",
        "emitter.beckn.spice.referrals-in.contract-id=e2c9a7b4-6f1d-4b3a-9c8e-7d6f5a4b3c2d",
        "emitter.beckn.spice.referrals-in.transaction-id=a41f6b2c-9d3e-4a7b-8c1d-2e3f4a5b6c7d",
        "emitter.beckn.spice.referrals-out.contract-id=7c3d9e1f-5a2b-4d6c-8e7f-0a1b2c3d4e5f",
        "emitter.beckn.spice.referrals-out.transaction-id=b52a7c3d-0e4f-4b8c-9d2e-3f4a5b6c7d8e"
})
class BecknModeIntegrationTest {

    private static final WireMockServer WM = new WireMockServer(options().dynamicPort());
    private static final Path CHECKPOINTS;

    static {
        WM.start();
        try {
            CHECKPOINTS = Files.createTempDirectory("beckn-it").resolve("checkpoints.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String HIE = "/bap/caller/update";
    private static final String BPP = "/bpp/caller/on_status";
    private static final String ACK = "{\"message\":{\"status\":\"ACK\",\"messageId\":\"x\"}}";
    private static final String NACK = "{\"message\":{\"status\":\"NACK\",\"messageId\":\"x\","
            + "\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"performance[0].id is required\"}}}";
    private static final String UNAVAILABLE = "{\"message\":{\"status\":\"NACK\",\"messageId\":\"x\","
            + "\"error\":{\"code\":\"COLLECTOR_UNAVAILABLE\",\"message\":\"try later\"}}}";

    private static final String ENCOUNTER = "{\"resourceType\":\"Encounter\",\"id\":\"7d2c9b1e\","
            + "\"status\":\"finished\",\"subject\":{\"reference\":\"Patient/1198503150001234\"}}";
    private static final String DIRECTION = "http://cce.openphc.org/CodeSystem/referral-direction";
    private static final String INBOUND_OUTCOME = "{\"resourceType\":\"ServiceRequest\",\"id\":\"0fe4c80e\",\"status\":\"completed\","
            + "\"intent\":\"order\",\"category\":[{\"coding\":[{\"system\":\"" + DIRECTION + "\",\"code\":\"inbound\"}]},"
            + "{\"coding\":[{\"system\":\"http://cce.openphc.org/CodeSystem/event-type\",\"code\":\"referral-response\"}]}],"
            + "\"subject\":{\"reference\":\"Patient/1198503150001234\"}}";
    private static final String OUTBOUND = "{\"resourceType\":\"ServiceRequest\",\"id\":\"a1b2\",\"status\":\"active\","
            + "\"intent\":\"order\",\"category\":[{\"coding\":[{\"system\":\"" + DIRECTION + "\",\"code\":\"outbound\"}]}],"
            + "\"performer\":[{\"reference\":\"Organization/0417\",\"identifier\":{\"system\":"
            + "\"http://cce.openphc.org/fhir/NamingSystem/facility-code\",\"value\":\"0417\"}}],"
            + "\"subject\":{\"reference\":\"Patient/1198503150001234\"}}";

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("emitter.beckn.onix.bap-caller-url", () -> WM.baseUrl() + "/bap/caller");
        registry.add("emitter.beckn.onix.bpp-caller-url", () -> WM.baseUrl() + "/bpp/caller/");
        registry.add("emitter.openmrs.base-url", () -> WM.baseUrl() + "/openmrs");
        registry.add("emitter.openhim.base-url", () -> WM.baseUrl() + "/fhir");
        registry.add("emitter.checkpoint.file-path", CHECKPOINTS::toString);
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @Autowired
    private BecknTargetForwarder forwarder;
    @Autowired
    private EventIdFactory eventIdFactory;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private FhirPollerService poller;

    @BeforeEach
    void reset() {
        WM.resetAll();
    }

    private static List<JsonNode> bodies(String path) {
        return WM.findAll(postRequestedFor(urlEqualTo(path))).stream()
                .map(LoggedRequest::getBodyAsString).map(BecknJson::parse).toList();
    }

    private double count(String metric, String... tags) {
        var counter = meterRegistry.find(metric).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void ackIsSuccess() {
        WM.stubFor(post(HIE).willReturn(okJson(ACK)));
        double before = count(OnixClient.METRIC_SENT, "leg", "cce-events", "outcome", "ack");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertTrue(result.isSuccess());
        List<JsonNode> sent = bodies(HIE);
        assertEquals(1, sent.size());
        JsonNode body = sent.get(0);
        assertEquals(eventIdFactory.eventId(BecknJson.parse(ENCOUNTER)), body.at("/message/contract/performance/0/id").asText());
        assertEquals("1198503150001234", body.at("/message/contract/performance/0/performanceAttributes/patientId").asText());
        assertEquals("Encounter", body.at("/message/contract/performance/0/performanceAttributes/eventType").asText());
        assertEquals(BecknJson.parse(ENCOUNTER), body.at("/message/contract/performance/0/performanceAttributes/resource"));
        assertEquals(before + 1, count(OnixClient.METRIC_SENT, "leg", "cce-events", "outcome", "ack"));
    }

    @Test
    void nackFailsWithoutRetry() {
        WM.stubFor(post(HIE).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(NACK)));
        double before = count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "nack");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertFalse(result.isSuccess());
        assertEquals(400, result.statusCode());
        assertEquals(1, bodies(HIE).size());
        assertEquals(before + 1, count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "nack"));
    }

    @Test
    void serverErrorThenAckIsRetriedWithNewMessageIdAndSamePerformanceId() {
        WM.stubFor(post(HIE).inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody(UNAVAILABLE))
                .willSetStateTo("second"));
        WM.stubFor(post(HIE).inScenario("retry").whenScenarioStateIs("second").willReturn(okJson(ACK)));
        double retriesBefore = count(OnixClient.METRIC_RETRIES, "leg", "cce-events", "action", "update");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertTrue(result.isSuccess());
        List<JsonNode> sent = bodies(HIE);
        assertEquals(2, sent.size());
        assertNotEquals(sent.get(0).at("/context/messageId").asText(), sent.get(1).at("/context/messageId").asText());
        assertEquals(sent.get(0).at("/message/contract/performance/0/id").asText(),
                sent.get(1).at("/message/contract/performance/0/id").asText());
        assertEquals(retriesBefore + 1, count(OnixClient.METRIC_RETRIES, "leg", "cce-events", "action", "update"));
    }

    @Test
    void noAnswerIsRetriedThenDropped() {
        WM.stubFor(post(HIE).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertFalse(result.isSuccess());
        assertEquals(3, bodies(HIE).size());
        assertTrue(count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "retries-exhausted") >= 1);
    }

    @Test
    void inboundReferralOutcomeGoesToHieAndAsOnStatusToPeer() {
        WM.stubFor(post(HIE).willReturn(okJson(ACK)));
        WM.stubFor(post(BPP).willReturn(okJson(ACK)));

        assertTrue(forwarder.forward(INBOUND_OUTCOME, "ServiceRequest").isSuccess());

        JsonNode hie = bodies(HIE).get(0);
        List<JsonNode> onStatus = bodies(BPP);
        assertEquals(1, onStatus.size());
        JsonNode peer = onStatus.get(0);
        assertEquals("on_status", peer.at("/context/action").asText());
        assertEquals("bap.spice.example.org", peer.at("/context/bapId").asText());
        assertEquals("e2c9a7b4-6f1d-4b3a-9c8e-7d6f5a4b3c2d", peer.at("/message/contract/id").asText());
        assertEquals("COMPLETED", peer.at("/message/contract/performance/0/status/code").asText());
        assertEquals(hie.at("/message/contract/performance/0/id"), peer.at("/message/contract/performance/0/id"));
    }

    @Test
    void outboundReferralToServedFacilityGoesToHieAndPeer() {
        WM.stubFor(post(HIE).willReturn(okJson(ACK)));

        assertTrue(forwarder.forward(OUTBOUND, "ServiceRequest").isSuccess());

        List<String> contracts = bodies(HIE).stream().map(b -> b.at("/message/contract/id").asText()).toList();
        assertEquals(List.of("5b1e7f3c-2a4d-4c6e-8f90-a1b2c3d4e5f6", "7c3d9e1f-5a2b-4d6c-8e7f-0a1b2c3d4e5f"), contracts);
        assertEquals(0, bodies(BPP).size());
    }

    @Test
    void hieNackDoesNotStopThePeerMessage() {
        WM.stubFor(post(HIE).withRequestBody(containing("5b1e7f3c-2a4d-4c6e-8f90-a1b2c3d4e5f6"))
                .willReturn(aResponse().withStatus(400).withBody(NACK)));
        WM.stubFor(post(BPP).willReturn(okJson(ACK)));

        ForwardResult result = forwarder.forward(INBOUND_OUTCOME, "ServiceRequest");

        assertFalse(result.isSuccess());
        assertEquals(1, bodies(HIE).size());
        assertEquals(1, bodies(BPP).size());
    }

    @Test
    void resourceWithoutPatientIsSkipped() {
        ForwardResult result = forwarder.forward("{\"resourceType\":\"Location\",\"id\":\"l1\"}", "Location");

        assertFalse(result.isSuccess());
        assertTrue(result.status().startsWith("skipped"));
        assertEquals(0, WM.getAllServeEvents().size());
        assertTrue(count(BecknTargetForwarder.METRIC_SKIPPED, "resourceType", "Location", "reason", "no-patient") >= 1);
    }

    @Test
    void pollerSendsToOnixAndNotToOpenhim() {
        String bundle = "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[{\"resource\":"
                + "{\"resourceType\":\"Encounter\",\"id\":\"enc-1\",\"meta\":{\"lastUpdated\":\"2026-11-13T08:00:12.000+00:00\"},"
                + "\"status\":\"finished\",\"subject\":{\"reference\":\"Patient/1198503150001234\"}}}]}";
        WM.stubFor(get(urlPathEqualTo("/openmrs/ws/fhir2/R4/Encounter")).willReturn(okJson(bundle)));
        WM.stubFor(post(HIE).willReturn(okJson(ACK)));

        ReflectionTestUtils.invokeMethod(poller, "pollResourceType", "Encounter");

        List<JsonNode> sent = bodies(HIE);
        assertEquals(1, sent.size());
        assertEquals("Encounter/enc-1",
                sent.get(0).at("/message/contract/performance/0/performanceAttributes/sourceEventId").asText());
        WM.verify(0, postRequestedFor(urlMatching("/fhir.*")));
    }

    @Test
    void healthChecksOnixNotOpenhim() {
        assertFalse(context.containsBean("openhim"));
        HealthIndicator onix = context.getBean("onix", HealthIndicator.class);
        assertEquals(Status.UP, onix.health().getStatus());
    }
}
