package gg.rc.parties;

import java.util.function.LongSupplier;

/** A hand-cranked clock, so invite TTLs are tested without sleeping. */
final class MutableClock implements LongSupplier {

    private long millis = 1_000_000L;

    @Override
    public long getAsLong() {
        return millis;
    }

    void advanceSeconds(long seconds) {
        millis += seconds * 1000L;
    }
}
