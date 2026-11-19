package su.prismaworld.logify.discord;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import su.prismaworld.logify.auth.AuthService;
import su.prismaworld.logify.auth.LoginResolution;
import su.prismaworld.logify.auth.RateLimiter;
import su.prismaworld.logify.auth.RegistrationCodes;
import su.prismaworld.logify.auth.RegistrationOutcome;
import su.prismaworld.logify.config.LogifyConfig;
import su.prismaworld.logify.config.LogifyConfig.DiscordMessages;
import su.prismaworld.logify.text.Texts;

/**
 * Принимает команды регистрации в канале и нажатия кнопок в запросах на вход.
 */
public final class DiscordListener extends ListenerAdapter {

    private final DiscordBot bot;
    private final AuthService authService;
    private final DiscordMessages messages;
    private final int accountLimit;
    private final RateLimiter rateLimiter;
    private final Logger logger;

    public DiscordListener(DiscordBot bot, AuthService authService, LogifyConfig config, Logger logger) {
        this.bot = bot;
        this.authService = authService;
        this.messages = config.discordMessages();
        this.accountLimit = config.auth().maxAccountsPerDiscord();
        this.rateLimiter = new RateLimiter(config.auth().attemptsPerMinute(), Duration.ofMinutes(1));
        this.logger = logger;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getChannel().getIdLong() != bot.registrationChannelId()) {
            return;
        }
        User author = event.getAuthor();
        if (author.isBot() || author.isSystem() || event.isWebhookMessage()) {
            return;
        }
        String[] words = event.getMessage().getContentRaw().strip().split("\\s+");
        if (!words[0].equalsIgnoreCase(bot.command())) {
            return;
        }
        delete(event.getMessage());

        MessageChannel channel = event.getChannel();
        if (!rateLimiter.tryAcquire(author.getIdLong())) {
            bot.notifyUser(author, channel, messages.registrationRateLimited());
            return;
        }
        Optional<String> code = words.length == 2 ? RegistrationCodes.normalize(words[1]) : Optional.empty();
        if (code.isEmpty()) {
            bot.notifyUser(author, channel, format(messages.registrationUsage(), "", ""));
            return;
        }
        authService.register(author.getIdLong(), code.get())
                .thenAccept(outcome -> bot.notifyUser(author, channel, describe(outcome, code.get())))
                .exceptionally(error -> {
                    logger.error("Ошибка при обработке команды регистрации от {}", author.getId(), error);
                    return null;
                });
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Optional<LoginButton> parsed = LoginButton.parse(event.getComponentId());
        if (parsed.isEmpty()) {
            return;
        }
        LoginButton button = parsed.get();
        LoginResolution resolution = authService.resolveLogin(event.getUser().getIdLong(), button.requestId(), button.approve());
        LoginRequestState state = switch (resolution) {
            case APPROVED -> LoginRequestState.APPROVED;
            case DENIED -> LoginRequestState.DENIED;
            case EXPIRED -> LoginRequestState.EXPIRED;
            case FOREIGN -> null;
        };
        if (state == null) {
            event.deferEdit().queue();
            return;
        }
        event.editMessageEmbeds(bot.resolvedLoginEmbed(state, button.username()))
                .setComponents()
                .queue(null, error -> logger.warn("Не удалось обновить запрос на вход {}", button.username(), error));
    }

    private String describe(RegistrationOutcome outcome, String code) {
        String username = outcome.username() == null ? "" : outcome.username();
        String template = switch (outcome.status()) {
            case SUCCESS -> messages.registrationSuccess();
            case INVALID_CODE -> messages.registrationInvalidCode();
            case LIMIT_REACHED -> messages.registrationLimit();
            case NAME_TAKEN -> messages.registrationAlreadyRegistered();
            case ERROR -> messages.registrationError();
        };
        return format(template, username, code);
    }

    private String format(String template, String username, String code) {
        return Texts.format(template, Map.of(
                "player", DiscordBot.escape(username),
                "code", DiscordBot.escape(code),
                "limit", Integer.toString(accountLimit),
                "command", bot.command()
        ));
    }

    private void delete(Message message) {
        try {
            message.delete().queue(null, new ErrorHandler()
                    .ignore(ErrorResponse.UNKNOWN_MESSAGE)
                    .handle(Throwable.class, error -> logger.warn("Не удалось удалить сообщение в канале регистрации", error)));
        } catch (PermissionException e) {
            logger.warn("Боту не хватает права «Управлять сообщениями» в канале регистрации: {}", e.getMessage());
        }
    }
}
