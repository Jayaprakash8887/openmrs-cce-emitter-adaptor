package org.openphc.cce.emitter.beckn;

/**
 * The three legs the emitter sends on. The tag value is used in metrics and log lines.
 * {@link #CCE_EVENTS} uses the Sygnal action {@code sygnal/event/report}; the two referral legs to
 * Spice use standard Beckn ({@code on_status}, {@code update}).
 */
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
