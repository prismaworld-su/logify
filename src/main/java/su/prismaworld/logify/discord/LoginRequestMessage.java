package su.prismaworld.logify.discord;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.requests.ErrorResponse;

/**
 * Отправленное личное сообщение с запросом на вход.
 */
public final class LoginRequestMessage {

    private final DiscordBot bot;
    private final Message message;
    private final String username;

    LoginRequestMessage(DiscordBot bot, Message message, String username) {
        this.bot = bot;
        this.message = message;
        this.username = username;
    }

    /**
     * Помечает запрос как недействительный и убирает кнопки.
     */
    public void markExpired() {
        message.editMessageEmbeds(bot.resolvedLoginEmbed(LoginRequestState.EXPIRED, username))
                .setComponents()
                .queue(null, new ErrorHandler()
                        .ignore(ErrorResponse.UNKNOWN_MESSAGE)
                        .handle(Throwable.class, error -> bot.logger().warn("Не удалось обновить запрос на вход {}", username, error)));
    }
}
