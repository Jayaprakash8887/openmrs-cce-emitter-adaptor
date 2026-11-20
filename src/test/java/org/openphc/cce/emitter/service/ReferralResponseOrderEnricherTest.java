package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link ReferralResponseOrderEnricher}: which referral order a Referral Response encounter's resources are
 * linked to, including when the poll sees the encounter before its DISCONTINUE order exists.
 */
class ReferralResponseOrderEnricherTest {

    private static final String BASE = "http://openmrs/openmrs";
    private static final String ENCOUNTER = "enc-1";
    private static final String PATIENT = "pat-1";
    private static final String RESPONSE_TYPE = "479e4805-90d9-44f5-b252-333d8a687e52";
    private static final String REFERRAL_IN_TYPE = "de0f5558-9813-4fa3-8613-467015f9fabd";
    private static final String EXTENSION_URL = "http://cce.openphc.org/StructureDefinition/source-referral-order";

    private MockRestServiceServer server;
    private ReferralResponseOrderEnricher enricher;

    @BeforeEach
    void setUp() {
        EmitterProperties properties = new EmitterProperties();
        properties.getOpenmrs().setBaseUrl(BASE);
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AuthService auth = mock(AuthService.class);
        when(auth.resolveOpenmrsAuthHeader(any())).thenReturn(null);
        enricher = new ReferralResponseOrderEnricher(properties, restTemplate, auth, new ObjectMapper(),
                new SimpleMeterRegistry());
    }

    @Test
    void links_the_referral_closed_by_the_encounters_discontinue_order() {
        expectEncounter(RESPONSE_TYPE, """
                [{"uuid":"dc-1","action":"DISCONTINUE","orderType":{"uuid":"778a9dc6-87d9-49c1-83d9-caa01041a8ad"},
                  "previousOrder":{"uuid":"ref-1","accessionNumber":"acc-1","instructions":"Referred for ANC Visit 1",
                                   "urgency":"ROUTINE","concept":{"uuid":"c-1","display":"Consultation"},
                                   "orderType":{"display":"Referral Order"}}}]""");

        Observation obs = observation();
        enricher.enrich(obs);

        assertThat(linkedOrder(obs)).isEqualTo("ref-1");
        assertThat(instructions(obs)).isEqualTo("Referred for ANC Visit 1");
        server.verify();
    }

    @Test
    void before_the_discontinue_order_exists_links_the_one_inbound_referral_open_at_the_encounter() {
        expectEncounter(RESPONSE_TYPE, "[]");
        expectReferralOrders("""
                [%s, %s, %s]""".formatted(
                // the one: in a Referral In encounter, open at 13:58:43 (stopped just after)
                referral("ref-open", "NEW", REFERRAL_IN_TYPE, "2026-11-19T13:57:28.000+0000", "2026-11-19T13:58:44.000+0000"),
                // an outbound referral (not a Referral In encounter)
                referral("ref-out", "NEW", "other-type", "2026-11-19T13:00:00.000+0000", null),
                // an inbound referral closed before the encounter
                referral("ref-old", "NEW", REFERRAL_IN_TYPE, "2026-11-18T10:00:00.000+0000", "2026-11-18T11:00:00.000+0000")));

        Observation obs = observation();
        enricher.enrich(obs);

        assertThat(linkedOrder(obs)).isEqualTo("ref-open");
        assertThat(instructions(obs)).isEqualTo("Referred for ANC Visit 1");
        server.verify();
    }

    @Test
    void two_open_inbound_referrals_are_not_guessed_between() {
        expectEncounter(RESPONSE_TYPE, "[]");
        expectReferralOrders("[%s, %s]".formatted(
                referral("ref-a", "NEW", REFERRAL_IN_TYPE, "2026-11-19T13:00:00.000+0000", null),
                referral("ref-b", "NEW", REFERRAL_IN_TYPE, "2026-11-19T13:10:00.000+0000", null)));

        Observation obs = observation();
        enricher.enrich(obs);

        assertThat(obs.getExtensionByUrl(EXTENSION_URL)).isNull();
        server.verify();
    }

    @Test
    void an_unresolved_referral_response_is_looked_up_again_for_the_next_resource() {
        // First resource: no DISCONTINUE yet and no open inbound referral → not linked, not cached.
        expectEncounter(RESPONSE_TYPE, "[]");
        expectReferralOrders("[]");
        Observation first = observation();
        enricher.enrich(first);
        assertThat(first.getExtensionByUrl(EXTENSION_URL)).isNull();
        server.verify();

        // Next resource of the same encounter: the DISCONTINUE order exists now.
        server.reset();
        expectEncounter(RESPONSE_TYPE, """
                [{"uuid":"dc-1","action":"DISCONTINUE","orderType":{"uuid":"778a9dc6-87d9-49c1-83d9-caa01041a8ad"},
                  "previousOrder":{"uuid":"ref-1","instructions":"Referred for ANC Visit 2"}}]""");
        Observation next = observation();
        enricher.enrich(next);
        assertThat(linkedOrder(next)).isEqualTo("ref-1");
        server.verify();
    }

    @Test
    void an_encounter_that_is_not_a_referral_response_is_cached() {
        expectEncounter("some-other-type", "[]");

        enricher.enrich(observation());
        enricher.enrich(observation());   // answered from the cache: one request only

        server.verify();
    }

    @Test
    void parses_openmrs_rest_datetimes() {
        assertThat(ReferralResponseOrderEnricher.parseOpenmrsDate("2026-11-19T13:58:44.000+0000"))
                .hasToString("2026-11-19T13:58:44Z");
        assertThat(ReferralResponseOrderEnricher.parseOpenmrsDate("not a date")).isNull();
        assertThat(ReferralResponseOrderEnricher.parseOpenmrsDate(null)).isNull();
    }

    // ---- helpers ----

    private void expectEncounter(String encounterType, String ordersJson) {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/ws/rest/v1/encounter/" + ENCOUNTER)))
                .andRespond(withSuccess("""
                        {"uuid":"%s","encounterDatetime":"2026-11-19T13:58:43.000+0000","patient":{"uuid":"%s"},
                         "encounterType":{"uuid":"%s"},"orders":%s}""".formatted(ENCOUNTER, PATIENT, encounterType, ordersJson),
                        MediaType.APPLICATION_JSON));
    }

    private void expectReferralOrders(String resultsJson) {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/ws/rest/v1/order?patient=" + PATIENT)))
                .andRespond(withSuccess("{\"results\":" + resultsJson + "}", MediaType.APPLICATION_JSON));
    }

    private static String referral(String uuid, String action, String encounterType, String activated, String stopped) {
        return """
                {"uuid":"%s","action":"%s","dateActivated":"%s","dateStopped":%s,"accessionNumber":"acc-%s",
                 "instructions":"Referred for ANC Visit 1","urgency":"ROUTINE",
                 "concept":{"uuid":"c-1","display":"Consultation"},"orderType":{"uuid":"778a9dc6-87d9-49c1-83d9-caa01041a8ad","display":"Referral Order"},
                 "encounter":{"encounterType":{"uuid":"%s"}}}""".formatted(
                uuid, action, activated, stopped == null ? "null" : "\"" + stopped + "\"", uuid, encounterType);
    }

    private static Observation observation() {
        Observation obs = new Observation();
        obs.setId("obs-1");
        obs.setEncounter(new Reference("Encounter/" + ENCOUNTER));
        return obs;
    }

    private static String linkedOrder(Observation obs) {
        Extension ext = obs.getExtensionByUrl(EXTENSION_URL);
        assertThat(ext).isNotNull();
        return ext.getExtensionByUrl("orderUuid").getValue().primitiveValue();
    }

    private static String instructions(Observation obs) {
        return ((CodeableConcept) obs.getExtensionByUrl(EXTENSION_URL).getExtensionByUrl("instructions").getValue()).getText();
    }
}
