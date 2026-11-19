package su.prismaworld.logify.discord;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
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
 * Принимает slash-команду регистрации и нажатия кнопок в запросах на вход.
 * Ответы на команду регистрации эфемерные: их видит только тот, кто её вызвал.
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
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals(bot.commandName())) {
            return;
        }
        if (event.getChannelIdLong() != bot.registrationChannelId()) {
            reply(event, format(messages.registrationWrongChannel(), "", ""));
            return;
        }
        long userId = event.getUser().getIdLong();
        if (!rateLimiter.tryAcquire(userId)) {
            reply(event, format(messages.registrationRateLimited(), "", ""));
            return;
        }
        String input = event.getOption(DiscordBot.CODE_OPTION, "", OptionMapping::getAsString);
        Optional<String> code = RegistrationCodes.normalize(input);
        if (code.isEmpty()) {
            reply(event, format(messages.registrationUsage(), "", ""));
            return;
        }

        // Сохранение в базу может занять больше 3 секунд, которые Discord даёт на ответ
        event.deferReply(true).queue();
        authService.register(userId, code.get()).whenComplete((outcome, error) -> {
            String text;
            if (error == null) {
                text = describe(outcome, code.get());
            } else {
                logger.error("Ошибка при обработке команды регистрации от {}", event.getUser().getId(), error);
                text = format(messages.registrationError(), "", "");
            }
            event.getHook().editOriginal(text).queue(null,
                    failure -> logger.warn("Не удалось ответить на команду регистрации {}", event.getUser().getId(), failure));
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

    private static void reply(SlashCommandInteractionEvent event, String text) {
        event.reply(text).setEphemeral(true).queue();
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
                "command", bot.command(),
                "channel", bot.registrationChannelMention()
        ));
    }
}
