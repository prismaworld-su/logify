package su.prismaworld.logify.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Duration DAY = Duration.ofDays(1);

    private static Account lastLogin(String ip, Instant at) {
        return new Account("Steve", 1, NOW.minus(Duration.ofDays(30)), ip, at);
    }

    @Test
    void sessionIsValidForSameIpWithinDuration() {
        assertTrue(lastLogin("10.0.0.1", NOW.minus(Duration.ofHours(23))).hasSession("10.0.0.1", DAY, NOW));
    }

    @Test
    void sessionRequiresSameIp() {
        assertFalse(lastLogin("10.0.0.1", NOW.minus(Duration.ofHours(1))).hasSession("10.0.0.2", DAY, NOW));
    }

    @Test
    void sessionExpires() {
        assertFalse(lastLogin("10.0.0.1", NOW.minus(Duration.ofHours(25))).hasSession("10.0.0.1", DAY, NOW));
    }

    @Test
    void sessionsCanBeDisabled() {
        assertFalse(lastLogin("10.0.0.1", NOW).hasSession("10.0.0.1", Duration.ZERO, NOW));
    }

    @Test
    void noSessionWithoutConfirmedLogin() {
        assertFalse(new Account("Steve", 1, NOW, null, null).hasSession("10.0.0.1", DAY, NOW));
    }

    @Test
    void sessionFromFutureIsIgnored() {
        assertFalse(lastLogin("10.0.0.1", NOW.plus(Duration.ofHours(1))).hasSession("10.0.0.1", DAY, NOW));
    }
}
