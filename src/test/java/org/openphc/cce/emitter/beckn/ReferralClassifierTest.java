package org.openphc.cce.emitter.beckn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.beckn.ReferralClassifier.Kind;
import org.openphc.cce.emitter.config.EmitterProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReferralClassifierTest {

    private static final String DIRECTION = "http://cce.openphc.org/CodeSystem/referral-direction";
    private static final String EVENT_TYPE = "http://cce.openphc.org/CodeSystem/event-type";
    private static final String FACILITY = "http://cce.openphc.org/fhir/NamingSystem/facility-code";

    private ReferralClassifier classifier;

    @BeforeEach
    void setUp() {
        EmitterProperties properties = new EmitterProperties();
        properties.getBeckn().getSpice().setFacilityCodes(List.of("0417"));
        classifier = new ReferralClassifier(properties);
    }

    private static String sr(String status, String direction, boolean response, String facilityCode) {
        StringBuilder categories = new StringBuilder();
        if (direction != null) {
            categories.append("{\"coding\":[{\"system\":\"" + DIRECTION + "\",\"code\":\"" + direction + "\"}]}");
        }
        if (response) {
            if (categories.length() > 0) categories.append(',');
            categories.append("{\"coding\":[{\"system\":\"" + EVENT_TYPE + "\",\"code\":\"referral-response\"}]}");
        }
        String performer = facilityCode == null ? "" : ",\"performer\":[{\"reference\":\"Organization/" + facilityCode
                + "\",\"identifier\":{\"system\":\"" + FACILITY + "\",\"value\":\"" + facilityCode + "\"}}]";
        return "{\"resourceType\":\"ServiceRequest\",\"id\":\"sr1\",\"status\":\"" + status + "\",\"category\":["
                + categories + "]" + performer + "}";
    }

    private ReferralClassifier.Decision classify(String json) {
        return classifier.classify(BecknJson.parse(json));
    }

    @Test
    void inboundCompletedResponseIsOutcomeCompleted() {
        var d = classify(sr("completed", "inbound", true, null));
        assertEquals(Kind.OUTCOME_TO_PEER, d.kind());
        assertEquals("COMPLETED", d.performanceStatus());
    }

    @Test
    void inboundRevokedResponseIsOutcomeDeclined() {
        var d = classify(sr("revoked", "inbound", true, null));
        assertEquals(Kind.OUTCOME_TO_PEER, d.kind());
        assertEquals("DECLINED", d.performanceStatus());
    }

    @Test
    void openInboundReferralIsNothing() {
        assertEquals(Kind.NONE, classify(sr("active", "inbound", false, null)).kind());
    }

    @Test
    void inboundWithResponseMarkerButOtherStatusIsNothing() {
        assertEquals(Kind.NONE, classify(sr("active", "inbound", true, null)).kind());
    }

    @Test
    void responseWithoutDirectionIsNothing() {
        assertEquals(Kind.NONE, classify(sr("completed", null, true, null)).kind());
    }

    @Test
    void outboundToServedFacilityIsReferralOut() {
        assertEquals(Kind.REFERRAL_OUT_TO_PEER, classify(sr("active", "outbound", false, "0417")).kind());
    }

    @Test
    void completedOutboundWithResponseMarkerIsReferralOutNotOutcome() {
        var d = classify(sr("completed", "outbound", true, "0417"));
        assertEquals(Kind.REFERRAL_OUT_TO_PEER, d.kind());
        assertNull(d.performanceStatus());
    }

    @Test
    void outboundToOtherFacilityIsNotServed() {
        assertEquals(Kind.REFERRAL_OUT_NOT_SERVED, classify(sr("active", "outbound", false, "9999")).kind());
    }

    @Test
    void outboundWithoutFacilityCodeIsNotServed() {
        assertEquals(Kind.REFERRAL_OUT_NOT_SERVED, classify(sr("active", "outbound", false, null)).kind());
    }

    @Test
    void otherResourcesAreNothing() {
        assertEquals(Kind.NONE, classify("{\"resourceType\":\"Encounter\",\"id\":\"e\"}").kind());
        assertEquals(Kind.NONE, classify("{\"resourceType\":\"ServiceRequest\",\"id\":\"lab\",\"status\":\"completed\"}").kind());
    }
}
