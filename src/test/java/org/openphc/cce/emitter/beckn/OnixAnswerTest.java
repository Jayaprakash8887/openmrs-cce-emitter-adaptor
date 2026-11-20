package org.openphc.cce.emitter.beckn;

import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.beckn.OnixAnswer.Verdict;

import static org.junit.jupiter.api.Assertions.*;

class OnixAnswerTest {

    private static final String ACK = "{\"message\":{\"status\":\"ACK\",\"messageId\":\"m\"}}";
    private static final String NACK = "{\"message\":{\"status\":\"NACK\",\"messageId\":\"m\","
            + "\"error\":{\"code\":\"SCH_REQUIRED_FIELD_MISSING\",\"message\":\"events[0].id is missing\"}}}";
    private static final String NACK_500 = "{\"message\":{\"status\":\"NACK\",\"messageId\":\"m\","
            + "\"error\":{\"code\":\"NET_DOWNSTREAM_UNAVAILABLE\",\"message\":\"down\"}}}";

    @Test
    void ok200AckIsAck() {
        OnixAnswer a = OnixAnswer.of(200, ACK);
        assertEquals(Verdict.ACK, a.verdict());
        assertEquals("ACK", a.result());
    }

    @Test
    void accepted202AckIsAck() {
        assertEquals(Verdict.ACK, OnixAnswer.of(202, ACK).verdict());
        assertEquals(Verdict.ACK, OnixAnswer.of(202, null).verdict());
    }

    @Test
    void ok200WithNackBodyIsNack() {
        assertEquals(Verdict.NACK, OnixAnswer.of(200, NACK).verdict());
    }

    @Test
    void http400NackIsNackWithError() {
        OnixAnswer a = OnixAnswer.of(400, NACK);
        assertEquals(Verdict.NACK, a.verdict());
        assertEquals("SCH_REQUIRED_FIELD_MISSING", a.errorCode());
        assertEquals("events[0].id is missing", a.errorMessage());
    }

    @Test
    void other4xxIsNackEvenWithoutBody() {
        assertEquals(Verdict.NACK, OnixAnswer.of(401, null).verdict());
        assertEquals(Verdict.NACK, OnixAnswer.of(404, "not json").verdict());
    }

    @Test
    void retriableStatuses() {
        assertEquals(Verdict.RETRY, OnixAnswer.of(500, NACK_500).verdict());
        assertEquals(Verdict.RETRY, OnixAnswer.of(503, "").verdict());
        assertEquals(Verdict.RETRY, OnixAnswer.of(408, null).verdict());
        assertEquals(Verdict.RETRY, OnixAnswer.of(429, null).verdict());
        assertEquals(Verdict.RETRY, OnixAnswer.noAnswer("SocketTimeoutException: Read timed out").verdict());
    }

    @Test
    void beckn1AckShapeIsAck() {
        assertEquals(Verdict.ACK, OnixAnswer.of(200, "{\"message\":{\"ack\":{\"status\":\"ACK\"}}}").verdict());
    }
}
