package su.prismaworld.logify.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft-аккаунт, привязанный к пользователю Discord.
 *
 * @param username ник в том виде, в котором он зарегистрирован
 * @param discordId ID пользователя Discord
 * @param registeredAt время регистрации
 * @param lastLoginIp IP последнего подтверждённого входа
 * @param lastLoginAt время последнего подтверждённого входа
 */
public record Account(
        String username,
        long discordId,
        Instant registeredAt,
        @Nullable String lastLoginIp,
        @Nullable Instant lastLoginAt
) {

    public Account {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(registeredAt, "registeredAt");
    }

    /**
     * Действует ли сессия: последний подтверждённый вход был с этого же IP не раньше {@code duration} назад.
     */
    public boolean hasSession(String ip, Duration duration, Instant now) {
        if (duration.isZero() || lastLoginIp == null || lastLoginAt == null || !lastLoginIp.equals(ip)) {
            return false;
        }
        return !lastLoginAt.isAfter(now) && lastLoginAt.plus(duration).isAfter(now);
    }
}
