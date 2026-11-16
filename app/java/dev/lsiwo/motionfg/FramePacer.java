package dev.lsiwo.motionfg;

/** Schedules phases of the current pair and keeps at most one pending source frame. */
final class FramePacer {
    private static final long SECOND = 1_000_000_000L;
    private static final long TIMER_TOLERANCE = 500_000L;
    static final int NONE = -1;
    private long rateLimit, capturePeriod, lastCapture, captureDue, start, pairVsync, lastDraw, phasePeriod;
    private long pairDuration;
    private int pending, pair, factor, slots, step;
    private boolean periodMeasured, lowLatency;
    private double slotRemainder;
    private long frontTime, pendingTime, pairTime;
    int front, target;
    float time;
    boolean startPair;

    FramePacer(int baseFps) {
        reset();
        setLimit(baseFps);
    }

    void reset() {
        front = pending = pair = target = NONE;
        frontTime = pairTime = pendingTime = 0;
        lastCapture = captureDue = start = lastDraw = 0;
        periodMeasured = false;
        slotRemainder = 0;
        capturePeriod = rateLimit == 0 ? SECOND / 30 : rateLimit;
        factor = step = 0;
    }

    void setLimit(int baseFps) {
        long limit = baseFps > 0 ? SECOND / baseFps : 0;
        if (rateLimit == limit) return;
        rateLimit = limit;
        captureDue = 0;
        capturePeriod = rateLimit == 0 ? SECOND / 30 : rateLimit;
        periodMeasured = false;
        reschedule();
    }

    void reschedule() {
        if (lowLatency && pair != NONE && step > 0) {
            front = pair;
            frontTime = pairTime;
        } else if (pair != NONE && pending == NONE) {
            pending = pair;
            pendingTime = pairTime;
        }
        pair = NONE;
        slotRemainder = 0;
    }

    void setLowLatency(boolean enabled) {
        if (lowLatency == enabled) return;
        reschedule();
        lowLatency = enabled;
    }

    boolean accepts(long captureTime) {
        return captureTime > lastCapture && (rateLimit == 0 || captureDue == 0
                || captureTime >= captureDue - Math.min(5_000_000L, rateLimit / 4));
    }

    int captureSlot(int count) {
        for (int i = 0; i < count; ++i) {
            if (i != front && i != pending && i != pair) return i;
        }
        // The latest frame replaces a queued frame while the displayed pair is still busy.
        return pending;
    }

    void submit(int slot, long timestamp) {
        if (lastCapture != 0) {
            long separation = timestamp - lastCapture;
            if (separation >= 2_000_000 && separation <= 125_000_000) {
                capturePeriod = periodMeasured ? (capturePeriod * 7 + separation) / 8 : separation;
                periodMeasured = true;
            }
        }
        if (rateLimit != 0) {
            captureDue = captureDue == 0 || timestamp - captureDue >= rateLimit
                    ? timestamp + rateLimit : captureDue + rateLimit;
        }
        lastCapture = timestamp;
        pending = slot;
        pendingTime = timestamp;
    }

    boolean hasWork() {
        return pair != NONE || pending != NONE;
    }

    boolean hasFreshSource() { return pending != NONE; }

    boolean advance(long now, long displayPeriod, int requested) {
        startPair = false;
        if (lastDraw != 0 && now - lastDraw < displayPeriod / 2) return false;
        if (!beginPair(now, displayPeriod, requested)) return false;
        if (expireForecasts(now)) return false;
        long elapsedSlots = Math.max(0, (now - start + pairVsync / 2) / pairVsync);
        int desired = 0;
        while (desired < factor && (desired * slots + factor / 2) / factor <= elapsedSlots) {
            ++desired;
        }
        if (desired <= step) return false;
        return phase(now, desired);
    }

    boolean advanceFree(long now, int requested) {
        startPair = false;
        if (!beginPair(now, 0, requested) || now + TIMER_TOLERANCE < nextFreePhase(now)) return false;
        if (expireForecasts(now)) return false;
        int desired = phasePeriod == 0 ? factor
                : (int) Math.min(factor, 1 + (now + TIMER_TOLERANCE - start) / phasePeriod);
        return desired > step && phase(now, desired);
    }

    long nextFreePhase(long now) {
        if (lowLatency && pending != NONE) return now;
        return pair == NONE ? (pending == NONE ? Long.MAX_VALUE : now) : start + (long) step * phasePeriod;
    }

    boolean outrunsDisplay(long vsync, int multiplier) {
        return Math.max(capturePeriod, rateLimit) + TIMER_TOLERANCE < vsync * multiplier;
    }

    private boolean beginPair(long now, long displayPeriod, int requested) {
        if (lowLatency && pending != NONE && pair != NONE) {
            // The current source was already shown, so a fresher capture supersedes its forecasts.
            if (step > 0) {
                front = pair;
                frontTime = pairTime;
            }
            pair = NONE;
        }
        if (pair != NONE) return true;
        if (pending == NONE) return false;
        pair = pending;
        pairTime = pendingTime;
        pending = NONE;
        long pairSpan = pairTime - frontTime;
        long cadence = Math.max(capturePeriod, rateLimit);
        boolean continuous = front != NONE && pairSpan > 0 && pairSpan <= 3 * cadence;
        pairDuration = continuous ? Math.min(cadence, pairSpan) : cadence;
        pairVsync = displayPeriod;
        if (displayPeriod > 0) {
            slots = allocateSlots(displayPeriod);
            factor = continuous ? Math.min(requested, slots) : 1;
        } else {
            factor = continuous ? requested : 1;
            phasePeriod = continuous ? Math.max(1L, pairDuration / factor) : 0L;
        }
        start = now;
        step = 0;
        startPair = !lowLatency && factor > 1;
        return true;
    }

    private boolean expireForecasts(long now) {
        if (!lowLatency || step == 0 || now - start < pairDuration) return false;
        commitPair();
        return true;
    }

    private boolean phase(long now, int desired) {
        if (lowLatency && step == 1 && desired > 1) startPair = true;
        step = desired;
        target = pair;
        time = lowLatency ? (step == 1 ? 1f : (float) (step - 1) / factor) : (float) step / factor;
        lastDraw = now;
        return true;
    }

    private int allocateSlots(long vsync) {
        double perPair = (double) Math.max(rateLimit, capturePeriod) / vsync;
        long rounded = Math.round(perPair);
        if (Math.abs(perPair - rounded) < 0.10) perPair = rounded;
        double budget = Math.max(1.0, perPair) + slotRemainder;
        int available = (int) budget;
        // Distribute fractional slot counts across pairs, for example 2, 3, 3
        // when 45 input FPS is displayed at 120 Hz.
        slotRemainder = budget - available;
        return available;
    }

    void presented() {
        if (step == factor) commitPair();
    }

    private void commitPair() {
        front = pair;
        frontTime = pairTime;
        pair = NONE;
    }
}
