package su.prismaworld.logify.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void limitsAttemptsWithinWindow() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(1), ticker);

        assertTrue(limiter.tryAcquire(1));
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
        // Другой пользователь не затронут
        assertTrue(limiter.tryAcquire(2));

        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        assertTrue(limiter.tryAcquire(1));
    }
}
