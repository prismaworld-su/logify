package su.prismaworld.logify.config;

import java.time.Duration;
import java.util.regex.Pattern;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.player.GameMode;

/**
 * Настройки плагина, прочитанные из {@code config.toml}.
 */
public record LogifyConfig(
        DatabaseSettings database,
        DiscordSettings discord,
        AuthSettings auth,
        PremiumSettings premium,
        LimboSettings limbo,
        GameMessages messages,
        DiscordMessages discordMessages
) {

    public record DatabaseSettings(
            String host,
            int port,
            String name,
            String username,
            String password,
            String tablePrefix,
            int poolSize
    ) {
    }

    /**
     * @param registrationChannelId ID канала регистрации, {@code 0}, если не задан
     */
    public record DiscordSettings(String token, long registrationChannelId, String command, String activity) {

        public boolean isConfigured() {
            return !token.isBlank() && registrationChannelId != 0;
        }
    }

    /**
     * @param sessionDuration {@link Duration#ZERO}, если сессии выключены
     * @param reminderInterval {@link Duration#ZERO}, если подсказка показывается один раз
     */
    public record AuthSettings(
            Duration registrationTimeout,
            Duration loginTimeout,
            Duration sessionDuration,
            int maxAccountsPerDiscord,
            Duration reminderInterval,
            Pattern nicknamePattern,
            int attemptsPerMinute
    ) {
    }

    public record PremiumSettings(boolean skipOnlineMode, boolean autoDetect) {
    }

    public record LimboSettings(Dimension dimension, GameMode gameMode) {
    }

    /**
     * Сообщения в игре в формате MiniMessage.
     */
    public record GameMessages(
            String registration,
            String commandHover,
            String registrationTitle,
            String registrationSubtitle,
            String registered,
            String login,
            String loginTitle,
            String loginSubtitle,
            String loginApproved,
            String kickLoginDenied,
            String kickRegistrationTimeout,
            String kickLoginTimeout,
            String kickDirectMessagesClosed,
            String kickUnavailable,
            String kickInvalidNickname,
            String kickWrongCase,
            String kickAlreadyAuthenticating,
            String kickNameTaken
    ) {
    }

    /**
     * Сообщения бота в Discord с плейсхолдерами вида {@code {player}}.
     */
    public record DiscordMessages(
            String registrationSuccess,
            String registrationInvalidCode,
            String registrationUsage,
            String registrationLimit,
            String registrationAlreadyRegistered,
            String registrationRateLimited,
            String registrationError,
            String directMessagesClosed,
            String loginRequestTitle,
            String loginRequest,
            String loginApproveButton,
            String loginDenyButton,
            String loginApproved,
            String loginDenied,
            String loginExpired
    ) {
    }
}
