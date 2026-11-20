package org.openphc.cce.emitter.beckn;

/**
 * The leg the emitter sends on: clinical events to the HIE, which passes them on to CCE. The tag
 * value is used in metrics and log lines.
 */
public enum Leg {
    CCE_EVENTS("cce-events");

    private final String tag;

    Leg(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
