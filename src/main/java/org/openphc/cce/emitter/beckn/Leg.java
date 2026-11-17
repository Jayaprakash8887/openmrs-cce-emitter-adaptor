package org.openphc.cce.emitter.beckn;

/** The three Beckn legs the emitter sends on. The tag value is used in metrics and log lines. */
public enum Leg {
    CCE_EVENTS("cce-events"),
    REFERRALS_IN("referrals-in"),
    REFERRALS_OUT("referrals-out");

    private final String tag;

    Leg(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
