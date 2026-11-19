package su.prismaworld.logify.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ограничение числа попыток за окно времени. Окно начинается с первой попытки.
 */
public final class RateLimiter {

    private final int limit;
    private final Cache<Long, AtomicInteger> attempts;

    public RateLimiter(int limit, Duration window) {
        this(limit, window, Ticker.systemTicker());
    }

    RateLimiter(int limit, Duration window, Ticker ticker) {
        this.limit = limit;
        // Счётчик не перезаписывается при попытках, поэтому запись истекает через window после первой попытки
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .ticker(ticker)
                .build();
    }

    /**
     * Учитывает попытку.
     *
     * @return {@code true}, если лимит ещё не превышен
     */
    public boolean tryAcquire(long key) {
        return attempts.get(key, ignored -> new AtomicInteger()).incrementAndGet() <= limit;
    }
}
