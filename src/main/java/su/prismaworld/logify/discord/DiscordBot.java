package su.prismaworld.logify.discord;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import su.prismaworld.logify.config.LogifyConfig.DiscordMessages;
import su.prismaworld.logify.config.LogifyConfig.DiscordSettings;
import su.prismaworld.logify.text.Texts;

/**
 * Discord-бот: запускается вместе с прокси, регистрирует slash-команду регистрации и отправляет запросы на вход.
 */
public final class DiscordBot {

    /** Параметр slash-команды регистрации с кодом. */
    public static final String CODE_OPTION = "code";

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    /** Код вводится как ABC-DEF или ABCDEF. */
    private static final int MIN_CODE_LENGTH = 6;
    private static final int MAX_CODE_LENGTH = 7;

    private final DiscordSettings settings;
    private final DiscordMessages messages;
    private final Logger logger;

    private volatile @Nullable JDA jda;
    private volatile boolean ready;
    private volatile boolean registrationAvailable;
    private volatile boolean stopping;

    public DiscordBot(DiscordSettings settings, DiscordMessages messages, Logger logger) {
        this.settings = settings;
        this.messages = messages;
        this.logger = logger;
    }

    /**
     * Подключается к Discord в отдельном потоке, чтобы не задерживать запуск прокси.
     */
    public void start(Object... listeners) {
        if (!settings.isConfigured()) {
            logger.error("Discord-бот не запущен: заполните discord.token и discord.registration-channel-id в config.toml");
            return;
        }
        Thread.ofPlatform().name("logify-discord-login").daemon().start(() -> login(listeners));
    }

    private void login(Object[] listeners) {
        // Slash-команды и кнопки приходят без дополнительных интентов: читать сообщения в каналах не нужно
        JDABuilder builder = JDABuilder.createLight(settings.token(), EnumSet.noneOf(GatewayIntent.class))
                .setEnableShutdownHook(false)
                .addEventListeners(new LifecycleListener())
                .addEventListeners(listeners);
        if (!settings.activity().isEmpty()) {
            builder.setActivity(Activity.playing(settings.activity()));
        }
        try {
            JDA instance = builder.build();
            this.jda = instance;
            if (stopping) {
                instance.shutdownNow();
            }
        } catch (InvalidTokenException e) {
            logger.error("Discord отклонил токен бота. Проверьте discord.token в config.toml");
        } catch (RuntimeException e) {
            logger.error("Не удалось запустить Discord-бота", e);
        }
    }

    /**
     * Готов ли бот отправлять запросы на вход.
     */
    public boolean isReady() {
        JDA instance = jda;
        if (!ready || instance == null) {
            return false;
        }
        JDA.Status status = instance.getStatus();
        return status != JDA.Status.SHUTTING_DOWN && status != JDA.Status.SHUTDOWN && status != JDA.Status.FAILED_TO_LOGIN;
    }

    /**
     * Готов ли бот принимать команды регистрации: подключён, видит канал регистрации и зарегистрировал slash-команду.
     */
    public boolean isRegistrationAvailable() {
        return isReady() && registrationAvailable;
    }

    public long registrationChannelId() {
        return settings.registrationChannelId();
    }

    /**
     * Имя slash-команды регистрации без "/".
     */
    public String commandName() {
        return settings.commandName();
    }

    /**
     * Команда регистрации в том виде, в котором её набирают в Discord, например {@code /r}.
     */
    public String command() {
        return settings.command();
    }

    /**
     * Название канала регистрации для подсказки в игре.
     */
    public String registrationChannelLabel() {
        JDA instance = jda;
        GuildMessageChannel channel = instance == null ? null
                : instance.getChannelById(GuildMessageChannel.class, settings.registrationChannelId());
        return channel == null ? "#" + settings.registrationChannelId() : "#" + channel.getName();
    }

    /**
     * Ссылка на канал регистрации в разметке Discord.
     */
    public String registrationChannelMention() {
        return "<#" + Long.toUnsignedString(settings.registrationChannelId()) + ">";
    }

    /**
     * Отправляет пользователю личное сообщение с кнопками «Подтвердить» и «Отклонить».
     * Если пользователь закрыл личные сообщения, future завершается {@link DirectMessagesClosedException}.
     */
    public CompletableFuture<LoginRequestMessage> sendLoginRequest(
            long discordId, String username, String ip, Instant time, String requestId) {
        JDA instance = jda;
        if (instance == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord-бот не запущен"));
        }
        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages.loginRequestTitle())
                .setDescription(Texts.format(messages.loginRequest(), Map.of(
                        "player", escape(username),
                        "ip", MarkdownSanitizer.escape(ip),
                        "time", "<t:" + time.getEpochSecond() + ":f>"
                )))
                .setColor(LoginRequestState.PENDING.color())
                .setTimestamp(time)
                .build();
        ActionRow buttons = ActionRow.of(
                Button.success(new LoginButton(true, requestId, username).id(), messages.loginApproveButton()),
                Button.danger(new LoginButton(false, requestId, username).id(), messages.loginDenyButton())
        );
        return instance.openPrivateChannelById(discordId)
                .flatMap(channel -> channel.sendMessageEmbeds(embed).setComponents(buttons))
                .submit()
                .handle((message, error) -> {
                    if (error == null) {
                        return new LoginRequestMessage(this, message, username);
                    }
                    Throwable cause = unwrap(error);
                    if (cause instanceof ErrorResponseException response
                            && response.getErrorResponse() == ErrorResponse.CANNOT_SEND_TO_USER) {
                        throw new DirectMessagesClosedException(discordId, cause);
                    }
                    throw new CompletionException(cause);
                });
    }

    /**
     * Сообщение запроса на вход после того, как он завершился. Кнопки при этом убираются.
     */
    public MessageEmbed resolvedLoginEmbed(LoginRequestState state, String username) {
        String template = switch (state) {
            case APPROVED -> messages.loginApproved();
            case DENIED -> messages.loginDenied();
            case EXPIRED, PENDING -> messages.loginExpired();
        };
        return new EmbedBuilder()
                .setTitle(messages.loginRequestTitle())
                .setDescription(Texts.format(template, Map.of("player", escape(username))))
                .setColor(state.color())
                .build();
    }

    public void shutdown() {
        stopping = true;
        ready = false;
        JDA instance = jda;
        if (instance == null) {
            return;
        }
        instance.shutdown();
        try {
            if (!instance.awaitShutdown(SHUTDOWN_TIMEOUT)) {
                instance.shutdownNow();
            }
        } catch (InterruptedException e) {
            instance.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    Logger logger() {
        return logger;
    }

    /**
     * Экранирует Markdown, чтобы "_" в нике не превращался в курсив.
     */
    public static String escape(String text) {
        return MarkdownSanitizer.escape(text);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class LifecycleListener extends ListenerAdapter {

        @Override
        public void onReady(ReadyEvent event) {
            ready = true;
            logger.info("Discord-бот {} подключён", event.getJDA().getSelfUser().getName());
            GuildMessageChannel channel = event.getJDA()
                    .getChannelById(GuildMessageChannel.class, settings.registrationChannelId());
            if (channel == null) {
                logger.error("Канал регистрации {} не найден. Проверьте discord.registration-channel-id и что бот добавлен на сервер",
                        settings.registrationChannelId());
                return;
            }
            registerCommand(channel.getGuild(), channel);
        }

        /**
         * Заменяет slash-команды этого бота на сервере командой регистрации. Команды других ботов не затрагиваются,
         * а устаревшая команда (например, после смены discord.command) удаляется.
         */
        private void registerCommand(Guild guild, GuildMessageChannel channel) {
            SlashCommandData command = Commands.slash(settings.commandName(), messages.commandDescription())
                    .addOptions(new OptionData(OptionType.STRING, CODE_OPTION, messages.commandCodeDescription(), true)
                            .setMinLength(MIN_CODE_LENGTH)
                            .setMaxLength(MAX_CODE_LENGTH));
            guild.updateCommands().addCommands(command).queue(
                    registered -> {
                        registrationAvailable = true;
                        logger.info("Команда {} доступна в канале #{} на сервере {}",
                                settings.command(), channel.getName(), guild.getName());
                    },
                    error -> logger.error("Не удалось зарегистрировать команду {} на сервере {}. "
                                    + "Пригласите бота заново с правом applications.commands",
                            settings.command(), guild.getName(), error));
        }

        @Override
        public void onShutdown(ShutdownEvent event) {
            ready = false;
            if (!stopping) {
                CloseCode code = event.getCloseCode();
                logger.error("Discord-бот отключился: {}", code == null ? "соединение закрыто" : code.getMeaning());
            }
        }
    }
}
