package su.prismaworld.logify.auth;

import org.jspecify.annotations.Nullable;

/**
 * Результат команды регистрации из Discord.
 *
 * @param username ник, к которому относится код, если код найден
 */
public record RegistrationOutcome(Status status, @Nullable String username) {

    public enum Status {
        SUCCESS,
        INVALID_CODE,
        LIMIT_REACHED,
        NAME_TAKEN,
        ERROR
    }

    static RegistrationOutcome of(Status status) {
        return new RegistrationOutcome(status, null);
    }

    static RegistrationOutcome of(Status status, String username) {
        return new RegistrationOutcome(status, username);
    }
}
