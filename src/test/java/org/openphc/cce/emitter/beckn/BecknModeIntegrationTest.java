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
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Beckn (Sygnal) mode end to end against a stubbed onix Sygnal caller (and a stubbed OpenMRS for the
 * poller test): 202 Ack, 400 Nack, 500-then-Ack retry with a new messageId and the same event id.
 */
@SpringBootTest(properties = {
        "emitter.target=beckn",
        "emitter.polling.fhir.enabled=false",
        "emitter.patient.national-id.enabled=false",
        "emitter.openhim.retry.max-attempts=3",
        "emitter.openhim.retry.backoff-ms=1",
        "emitter.beckn.onix.timeout=2s",
        "emitter.beckn.self.network-id=sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry",
        "emitter.beckn.hie.uri=https://hie.example.org/sygnal/receiver"
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

    /** The action is appended with its slashes as they are: no %2F. */
    private static final String REPORT = "/sygnal/caller/sygnal/event/report";
    private static final String ACK = "{\"message\":{\"status\":\"ACK\",\"messageId\":\"x\"}}";
    private static final String NACK = "{\"message\":{\"status\":\"NACK\",\"messageId\":\"x\","
            + "\"error\":{\"code\":\"SCH_REQUIRED_FIELD_MISSING\",\"message\":\"events[0].id is missing\"}}}";
    private static final String UNAVAILABLE = "{\"message\":{\"status\":\"NACK\",\"messageId\":\"x\","
            + "\"error\":{\"code\":\"NET_DOWNSTREAM_UNAVAILABLE\",\"message\":\"try later\"}}}";

    private static final String ENCOUNTER = "{\"resourceType\":\"Encounter\",\"id\":\"7d2c9b1e\","
            + "\"meta\":{\"lastUpdated\":\"2026-11-13T08:00:12.000+00:00\"},"
            + "\"status\":\"finished\",\"subject\":{\"reference\":\"Patient/1198503150001234\"}}";
    private static final String DIRECTION = "http://cce.openphc.org/CodeSystem/referral-direction";
    private static final String OUTBOUND_REFERRAL = "{\"resourceType\":\"ServiceRequest\",\"id\":\"a1b2\",\"status\":\"active\","
            + "\"intent\":\"order\",\"category\":[{\"coding\":[{\"system\":\"" + DIRECTION + "\",\"code\":\"outbound\"}]}],"
            + "\"performer\":[{\"reference\":\"Organization/0417\",\"identifier\":{\"system\":"
            + "\"http://cce.openphc.org/fhir/NamingSystem/facility-code\",\"value\":\"0417\"}}],"
            + "\"subject\":{\"reference\":\"Patient/1198503150001234\"}}";

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("emitter.beckn.onix.caller-url", () -> WM.baseUrl() + "/sygnal/caller");
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

    private static List<JsonNode> bodies() {
        return WM.findAll(postRequestedFor(urlEqualTo(REPORT))).stream()
                .map(LoggedRequest::getBodyAsString).map(BecknJson::parse).toList();
    }

    private static void noOtherCalls() {
        assertTrue(WM.getAllServeEvents().stream().allMatch(e -> REPORT.equals(e.getRequest().getUrl())),
                () -> WM.getAllServeEvents().stream().map(e -> e.getRequest().getUrl()).toList().toString());
        assertTrue(WM.getAllServeEvents().stream().noneMatch(e -> e.getRequest().getUrl().contains("%2F")));
    }

    private double count(String metric, String... tags) {
        var counter = meterRegistry.find(metric).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void accepted202AckIsSuccessAndTheBodyIsAnEventReport() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody(ACK)));
        double before = count(OnixClient.METRIC_SENT, "leg", "cce-events", "action", "sygnal/event/report",
                "outcome", "ack");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertTrue(result.isSuccess());
        assertEquals(202, result.statusCode());
        noOtherCalls();
        List<JsonNode> sent = bodies();
        assertEquals(1, sent.size());
        JsonNode body = sent.get(0);

        JsonNode ctx = body.get("context");
        assertEquals("2.0.0", ctx.get("version").asText());
        assertEquals("sygnal/event/report", ctx.get("action").asText());
        assertEquals("sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry", ctx.get("networkId").asText());
        assertEquals("bap.openmrs.nextsteps.mdtlabs.org", ctx.get("senderId").asText());
        assertEquals("bap.openhim.nextsteps.mdtlabs.org", ctx.get("receiverId").asText());
        assertEquals("https://hie.example.org/sygnal/receiver", ctx.get("receiverUri").asText());
        UUID.fromString(ctx.get("transactionId").asText());
        UUID.fromString(ctx.get("messageId").asText());
        assertEquals(9, ctx.size());
        ctx.fieldNames().forEachRemaining(name -> assertFalse(name.startsWith("bap") || name.startsWith("bpp"), name));

        JsonNode message = body.get("message");
        assertEquals(1, message.size());
        assertEquals(1, message.get("events").size());
        JsonNode event = message.at("/events/0");
        assertFalse(event.has("@context"));
        assertEquals("sygnal:CoordinationEvent", event.get("@type").asText());
        assertEquals(eventIdFactory.eventId(BecknJson.parse(ENCOUNTER)), event.get("id").asText());
        assertEquals(1, event.get("revision").asInt());
        assertEquals("DELIVERY_RECORDED", event.get("eventType").asText());
        assertEquals("INDIVIDUAL", event.get("granularity").asText());
        assertEquals("bap.openmrs.nextsteps.mdtlabs.org", event.at("/from/id").asText());
        assertEquals("PROVIDER", event.at("/from/role").asText());
        assertEquals("2026-11-13T08:00:12Z", event.get("occurredAt").asText());

        JsonNode attrs = event.get("eventAttributes");
        assertEquals("cce:CceClinicalEvent", attrs.get("@type").asText());
        assertEquals("https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v1.0/context.jsonld",
                attrs.get("@context").asText());
        assertEquals("Encounter", attrs.get("eventType").asText());
        assertEquals("1198503150001234", attrs.get("patientId").asText());
        assertEquals("4.0.1", attrs.get("fhirVersion").asText());
        assertEquals("Encounter/7d2c9b1e", attrs.get("sourceEventId").asText());
        assertEquals(BecknJson.parse(ENCOUNTER), attrs.get("resource"));

        assertEquals(before + 1, count(OnixClient.METRIC_SENT, "leg", "cce-events", "action", "sygnal/event/report",
                "outcome", "ack"));
    }

    @Test
    void nack400FailsWithoutRetry() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(NACK)));
        double before = count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "nack");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertFalse(result.isSuccess());
        assertEquals(400, result.statusCode());
        assertEquals(1, bodies().size());
        assertEquals(before + 1, count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "nack"));
    }

    @Test
    void serverErrorThenAckIsRetriedWithNewMessageIdAndSameEventAndTransaction() {
        WM.stubFor(post(urlEqualTo(REPORT)).inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody(UNAVAILABLE))
                .willSetStateTo("second"));
        WM.stubFor(post(urlEqualTo(REPORT)).inScenario("retry").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody(ACK)));
        double retriesBefore = count(OnixClient.METRIC_RETRIES, "leg", "cce-events", "action", "sygnal/event/report");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertTrue(result.isSuccess());
        List<JsonNode> sent = bodies();
        assertEquals(2, sent.size());
        assertNotEquals(sent.get(0).at("/context/messageId").asText(), sent.get(1).at("/context/messageId").asText());
        assertEquals(sent.get(0).at("/context/transactionId"), sent.get(1).at("/context/transactionId"));
        assertEquals(sent.get(0).at("/message/events/0/id").asText(), sent.get(1).at("/message/events/0/id").asText());
        assertEquals(retriesBefore + 1, count(OnixClient.METRIC_RETRIES, "leg", "cce-events", "action", "sygnal/event/report"));
    }

    @Test
    void noAnswerIsRetriedThenDropped() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertFalse(result.isSuccess());
        assertEquals(3, bodies().size());
        assertTrue(count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "retries-exhausted") >= 1);
    }

    @Test
    void eventsGetTheirOwnTransactionIdWhenNoneIsSet() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(okJson(ACK)));

        forwarder.forward(ENCOUNTER, "Encounter");
        forwarder.forward(OUTBOUND_REFERRAL, "ServiceRequest");

        List<JsonNode> sent = bodies();
        assertEquals(2, sent.size());
        assertNotEquals(sent.get(0).at("/context/transactionId"), sent.get(1).at("/context/transactionId"));
    }

    @Test
    void aReferralIsOneEventReportToTheHieOnly() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(okJson(ACK)));

        assertTrue(forwarder.forward(OUTBOUND_REFERRAL, "ServiceRequest").isSuccess());

        noOtherCalls();
        List<JsonNode> sent = bodies();
        assertEquals(1, sent.size());
        assertEquals("bap.openhim.nextsteps.mdtlabs.org", sent.get(0).at("/context/receiverId").asText());
        assertEquals(BecknJson.parse(OUTBOUND_REFERRAL), sent.get(0).at("/message/events/0/eventAttributes/resource"));
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
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(okJson(ACK)));

        ReflectionTestUtils.invokeMethod(poller, "pollResourceType", "Encounter");

        List<JsonNode> sent = bodies();
        assertEquals(1, sent.size());
        assertEquals("Encounter/enc-1", sent.get(0).at("/message/events/0/eventAttributes/sourceEventId").asText());
        assertEquals("2026-11-13T08:00:12Z", sent.get(0).at("/message/events/0/occurredAt").asText());
        WM.verify(0, postRequestedFor(urlMatching("/fhir.*")));
    }

    @Test
    void healthChecksTheSygnalCallerNotOpenhim() {
        assertFalse(context.containsBean("openhim"));
        HealthIndicator onix = context.getBean("onix", HealthIndicator.class);
        assertEquals(Status.UP, onix.health().getStatus());
        WM.verify(1, getRequestedFor(urlEqualTo(REPORT)));
    }
}
