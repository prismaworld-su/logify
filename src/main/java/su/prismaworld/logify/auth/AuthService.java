package su.prismaworld.logify.auth;

import com.velocitypowered.api.proxy.Player;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import su.prismaworld.logify.config.LogifyConfig;
import su.prismaworld.logify.config.LogifyConfig.GameMessages;
import su.prismaworld.logify.discord.DiscordBot;
import su.prismaworld.logify.limbo.AuthLimbo;
import su.prismaworld.logify.storage.Account;
import su.prismaworld.logify.storage.AccountRepository;
import su.prismaworld.logify.text.Texts;

/**
 * Логика авторизации: решает, что делать с игроком при входе, и связывает игроков в мире ожидания с Discord.
 */
public final class AuthService {

    private static final int REQUEST_ID_BYTES = 9;

    private final LogifyConfig config;
    private final AccountRepository accounts;
    private final DiscordBot discord;
    private final AuthLimbo limbo;
    private final Logger logger;

    private final ExecutorService databaseExecutor;
    /** Регистрации выполняются по одной, чтобы проверка лимита аккаунтов и вставка не пересекались. */
    private final ExecutorService registrationExecutor;

    private final RegistrationCodes codes = new RegistrationCodes();
    private final SecureRandom random = new SecureRandom();

    /** Игроки в мире ожидания по нику в нижнем регистре. */
    private final Map<String, AuthSession> active = new ConcurrentHashMap<>();
    private final Map<String, RegistrationSession> registrations = new ConcurrentHashMap<>();
    private final Map<String, LoginSession> logins = new ConcurrentHashMap<>();

    public AuthService(LogifyConfig config, AccountRepository accounts, DiscordBot discord, AuthLimbo limbo, Logger logger) {
        this.config = config;
        this.accounts = accounts;
        this.discord = discord;
        this.limbo = limbo;
        this.logger = logger;
        this.databaseExecutor = Executors.newFixedThreadPool(config.database().poolSize(),
                Thread.ofPlatform().name("logify-database-", 0).daemon().factory());
        this.registrationExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("logify-registration").daemon().factory());
    }

    // ---- Вход на прокси ----

    /**
     * Решает, что делать с игроком: пустить по сессии, зарегистрировать, запросить подтверждение или отключить.
     */
    public CompletableFuture<AuthAction> decide(Player player) {
        String username = player.getUsername();
        String ip = ip(player);
        return query(() -> accounts.find(username))
                .thenApply(found -> decide(username, ip, found))
                .exceptionally(error -> {
                    logger.error("Не удалось проверить аккаунт {}", username, unwrap(error));
                    return deny(messages().kickUnavailable());
                });
    }

    private AuthAction decide(String username, String ip, Optional<Account> found) {
        if (found.isEmpty()) {
            return discord.isRegistrationAvailable() ? new AuthAction.Register() : deny(messages().kickUnavailable());
        }
        Account account = found.get();
        if (!account.username().equals(username)) {
            // Офлайн-UUID зависит от регистра ника, поэтому ник должен совпадать в точности
            return deny(messages().kickWrongCase(), Placeholder.unparsed("registered", account.username()));
        }
        if (account.hasSession(ip, config.auth().sessionDuration(), Instant.now())) {
            logger.info("{} вошёл по сессии с {}", username, ip);
            return new AuthAction.Allow();
        }
        return discord.isReady() ? new AuthAction.Login(account) : deny(messages().kickUnavailable());
    }

    /**
     * Отправляет игрока в мир ожидания. Вызывается LimboAPI в очереди входа.
     */
    public void start(Player player, AuthAction action) {
        switch (action) {
            case AuthAction.Allow ignored -> {
            }
            case AuthAction.Register ignored -> limbo.spawn(player, new RegistrationSession(this, player));
            case AuthAction.Login login -> limbo.spawn(player, new LoginSession(this, player, login.account()));
            case AuthAction.Deny deny -> player.disconnect(deny.reason());
        }
    }

    // ---- Команды из Discord ----

    /**
     * Регистрирует аккаунт по коду, который пользователь отправил в канал регистрации.
     */
    public CompletableFuture<RegistrationOutcome> register(long discordId, String code) {
        RegistrationSession session = registrations.get(code);
        if (session == null || !session.tryBeginProcessing()) {
            return CompletableFuture.completedFuture(RegistrationOutcome.of(RegistrationOutcome.Status.INVALID_CODE));
        }
        String username = session.username();
        Instant now = Instant.now();
        // Регистрация сразу открывает сессию, чтобы повторный вход с того же IP не требовал подтверждения
        Account account = new Account(username, discordId, now, session.ip, now);
        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return accounts.create(account, config.auth().maxAccountsPerDiscord());
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                }, registrationExecutor)
                .handle((result, error) -> {
                    if (error != null) {
                        logger.error("Не удалось зарегистрировать {}", username, unwrap(error));
                        session.abortProcessing();
                        return RegistrationOutcome.of(RegistrationOutcome.Status.ERROR, username);
                    }
                    return switch (result) {
                        case CREATED -> {
                            logger.info("{} зарегистрирован и привязан к Discord {}", username, discordId);
                            registrations.remove(code, session);
                            session.completeRegistered();
                            yield RegistrationOutcome.of(RegistrationOutcome.Status.SUCCESS, username);
                        }
                        case LIMIT_REACHED -> {
                            session.abortProcessing();
                            yield RegistrationOutcome.of(RegistrationOutcome.Status.LIMIT_REACHED, username);
                        }
                        case NAME_TAKEN -> {
                            registrations.remove(code, session);
                            session.failNameTaken();
                            yield RegistrationOutcome.of(RegistrationOutcome.Status.NAME_TAKEN, username);
                        }
                    };
                });
    }

    /**
     * Обрабатывает кнопку «Подтвердить» или «Отклонить» в запросе на вход.
     */
    public LoginResolution resolveLogin(long discordUserId, String requestId, boolean approve) {
        LoginSession session = logins.get(requestId);
        if (session == null) {
            return LoginResolution.EXPIRED;
        }
        LoginResolution resolution = session.resolve(discordUserId, approve);
        if (resolution == LoginResolution.APPROVED || resolution == LoginResolution.DENIED) {
            logins.remove(requestId, session);
            logger.info("Вход {} с {} {} в Discord", session.username(), session.ip,
                    resolution == LoginResolution.APPROVED ? "подтверждён" : "отклонён");
        }
        return resolution;
    }

    // ---- Администрирование ----

    public CompletableFuture<Optional<Account>> findAccount(String username) {
        return query(() -> accounts.find(username));
    }

    public CompletableFuture<List<Account>> findAccounts(long discordId) {
        return query(() -> accounts.findByDiscord(discordId));
    }

    public CompletableFuture<Boolean> unregister(String username) {
        return query(() -> accounts.delete(username));
    }

    public CompletableFuture<Boolean> resetSession(String username) {
        return query(() -> accounts.clearSession(username));
    }

    public void shutdown() {
        registrationExecutor.shutdown();
        databaseExecutor.shutdown();
        try {
            if (!registrationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                registrationExecutor.shutdownNow();
            }
            if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                databaseExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            registrationExecutor.shutdownNow();
            databaseExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- Для сессий ----

    LogifyConfig config() {
        return config;
    }

    GameMessages messages() {
        return config.messages();
    }

    DiscordBot discord() {
        return discord;
    }

    Logger logger() {
        return logger;
    }

    Component message(String template, TagResolver... resolvers) {
        return Texts.render(template, resolvers);
    }

    boolean activate(AuthSession session) {
        return active.putIfAbsent(key(session.username()), session) == null;
    }

    boolean deactivate(AuthSession session) {
        return active.remove(key(session.username()), session);
    }

    String issueRegistrationCode(RegistrationSession session) {
        while (true) {
            String code = codes.generate();
            if (registrations.putIfAbsent(code, session) == null) {
                return code;
            }
        }
    }

    void releaseRegistrationCode(@Nullable String code, RegistrationSession session) {
        if (code != null) {
            registrations.remove(code, session);
        }
    }

    String issueLoginRequest(LoginSession session) {
        byte[] bytes = new byte[REQUEST_ID_BYTES];
        while (true) {
            random.nextBytes(bytes);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (logins.putIfAbsent(id, session) == null) {
                return id;
            }
        }
    }

    void releaseLoginRequest(@Nullable String requestId, LoginSession session) {
        if (requestId != null) {
            logins.remove(requestId, session);
        }
    }

    /**
     * Сохраняет подтверждённый вход, чтобы открыть сессию для этого IP.
     */
    void rememberLogin(String username, String ip) {
        Instant now = Instant.now();
        query(() -> {
            accounts.updateLastLogin(username, ip, now);
            return null;
        }).exceptionally(error -> {
            logger.warn("Не удалось сохранить сессию {}", username, unwrap(error));
            return null;
        });
    }

    static String ip(Player player) {
        InetSocketAddress address = player.getRemoteAddress();
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private AuthAction deny(String template, TagResolver... resolvers) {
        return new AuthAction.Deny(Texts.render(template, resolvers));
    }

    private <T> CompletableFuture<T> query(SqlCall<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.run();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, databaseExecutor);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T run() throws SQLException;
    }
}
