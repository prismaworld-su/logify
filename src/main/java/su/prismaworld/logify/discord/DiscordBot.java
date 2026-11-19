package su.prismaworld.logify.discord;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
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
 * Discord-бот: запускается вместе с прокси, принимает команды регистрации и отправляет запросы на вход.
 */
public final class DiscordBot {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final long CHANNEL_NOTICE_LIFETIME_SECONDS = 15;

    private final DiscordSettings settings;
    private final DiscordMessages messages;
    private final Logger logger;

    private volatile @Nullable JDA jda;
    private volatile boolean ready;
    private volatile boolean registrationChannelAvailable;
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
        JDABuilder builder = JDABuilder.createLight(settings.token(), GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
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
     * Готов ли бот принимать команды регистрации: подключён и видит канал регистрации.
     */
    public boolean isRegistrationAvailable() {
        return isReady() && registrationChannelAvailable;
    }

    public long registrationChannelId() {
        return settings.registrationChannelId();
    }

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

    /**
     * Отправляет пользователю личное сообщение. Если личные сообщения закрыты, на время пишет об этом
     * в {@code fallback} с упоминанием пользователя.
     */
    public void notifyUser(User user, MessageChannel fallback, String text) {
        user.openPrivateChannel()
                .flatMap(channel -> channel.sendMessage(text))
                .queue(null, new ErrorHandler()
                        .handle(ErrorResponse.CANNOT_SEND_TO_USER, error -> notifyInChannel(user, fallback))
                        .handle(Throwable.class, error -> logger.warn("Не удалось отправить личное сообщение {}", user.getId(), error)));
    }

    private void notifyInChannel(User user, MessageChannel channel) {
        String text = Texts.format(messages.directMessagesClosed(), Map.of("mention", user.getAsMention()));
        channel.sendMessage(text)
                .setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                .queue(message -> message.delete().queueAfter(CHANNEL_NOTICE_LIFETIME_SECONDS, TimeUnit.SECONDS,
                                null, new ErrorHandler().ignore(ErrorResponse.UNKNOWN_MESSAGE)),
                        error -> logger.warn("Не удалось отправить сообщение в канал регистрации", error));
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
            GuildMessageChannel channel = event.getJDA()
                    .getChannelById(GuildMessageChannel.class, settings.registrationChannelId());
            if (channel == null) {
                logger.error("Канал регистрации {} не найден. Проверьте discord.registration-channel-id и что бот добавлен на сервер",
                        settings.registrationChannelId());
            } else {
                Member self = channel.getGuild().getSelfMember();
                EnumSet<Permission> required = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                        Permission.MESSAGE_MANAGE, Permission.MESSAGE_HISTORY);
                EnumSet<Permission> missing = EnumSet.noneOf(Permission.class);
                for (Permission permission : required) {
                    if (!self.hasPermission(channel, permission)) {
                        missing.add(permission);
                    }
                }
                if (!missing.isEmpty()) {
                    logger.warn("У бота нет прав в канале #{}: {}", channel.getName(), missing);
                }
                registrationChannelAvailable = true;
            }
            ready = true;
            logger.info("Discord-бот {} подключён", event.getJDA().getSelfUser().getName());
        }

        @Override
        public void onShutdown(ShutdownEvent event) {
            ready = false;
            if (event.getCloseCode() == CloseCode.DISALLOWED_INTENTS) {
                logger.error("Discord отклонил подключение: включите Message Content Intent в Discord Developer Portal -> Bot");
            } else if (!stopping) {
                logger.error("Discord-бот отключился: {}", event.getCloseCode());
            }
        }
    }
}
