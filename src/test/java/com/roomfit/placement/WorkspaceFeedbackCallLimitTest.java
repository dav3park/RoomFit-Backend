package com.roomfit.placement;

import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class WorkspaceFeedbackCallLimitTest {
    @Test
    void thirdConcurrentRequestIsRejectedAndReleaseAllowsNextRequest() {
        WorkspaceFeedbackCallLimit limit = new WorkspaceFeedbackCallLimit();
        limit.acquire(); limit.acquire();
        assertThatThrownBy(limit::acquire).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FEEDBACK_RATE_LIMITED));
        limit.release(); limit.acquire(); limit.release(); limit.release();
    }

    @Test
    void twentyCallsConsumeWindowAndRejectedCallDoesNotPreventRecovery() {
        AtomicLong now = new AtomicLong();
        WorkspaceFeedbackCallLimit limit = new WorkspaceFeedbackCallLimit(now::get);
        for (int i = 0; i < 20; i++) { limit.acquire(); limit.release(); }
        assertThatThrownBy(limit::acquire).isInstanceOf(CustomException.class);
        now.set(Duration.ofMinutes(1).toNanos());
        assertThatCode(() -> { limit.acquire(); limit.release(); }).doesNotThrowAnyException();
    }
}
