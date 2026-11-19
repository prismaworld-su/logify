package su.prismaworld.logify.discord;

import java.util.Optional;

/**
 * Кнопка запроса на вход. Ник хранится в ID кнопки, чтобы обновить сообщение даже после перезапуска прокси.
 *
 * @param approve {@code true} для кнопки «Подтвердить»
 * @param requestId ID запроса на вход
 * @param username ник игрока
 */
public record LoginButton(boolean approve, String requestId, String username) {

    private static final String PREFIX = "logify:login:";
    private static final String APPROVE = "approve";
    private static final String DENY = "deny";

    public String id() {
        return PREFIX + (approve ? APPROVE : DENY) + ":" + requestId + ":" + username;
    }

    public static Optional<LoginButton> parse(String id) {
        if (!id.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = id.substring(PREFIX.length()).split(":", 3);
        if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
            return Optional.empty();
        }
        return switch (parts[0]) {
            case APPROVE -> Optional.of(new LoginButton(true, parts[1], parts[2]));
            case DENY -> Optional.of(new LoginButton(false, parts[1], parts[2]));
            default -> Optional.empty();
        };
    }
}
