package com.roomfit.placement;

import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import java.time.Duration;
import java.util.function.LongSupplier;

/** A process-wide cap for the public pilot endpoint, independent of spoofable client IDs. */
final class WorkspaceFeedbackCallLimit {
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();
    private final LongSupplier clock;
    private long windowStart;
    private int requests;
    private int active;

    WorkspaceFeedbackCallLimit() { this(System::nanoTime); }
    WorkspaceFeedbackCallLimit(LongSupplier clock) { this.clock = clock; this.windowStart = clock.getAsLong(); }

    synchronized void acquire() {
        long now = clock.getAsLong();
        if (now - windowStart >= WINDOW) { windowStart = now; requests = 0; }
        if (active >= 2 || requests >= 20) throw new CustomException(ErrorCode.FEEDBACK_RATE_LIMITED);
        active++;
        requests++;
    }

    synchronized void release() { active--; }
}
