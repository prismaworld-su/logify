package su.prismaworld.logify.auth;

import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;
import su.prismaworld.logify.config.LogifyConfig.GameMessages;
import su.prismaworld.logify.discord.DirectMessagesClosedException;
import su.prismaworld.logify.discord.LoginRequestMessage;
import su.prismaworld.logify.storage.Account;
import su.prismaworld.logify.text.Texts;

/**
 * Вход в зарегистрированный аккаунт: владелец подтверждает или отклоняет его кнопкой в личных сообщениях Discord.
 */
final class LoginSession extends AuthSession {

    private enum State {
        WAITING,
        APPROVED,
        DENIED,
        /** Время вышло, игрок вышел или запрос не удалось отправить. */
        EXPIRED
    }

    private final Account account;
    private final Duration timeout;
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
    private volatile @Nullable String requestId;
    /** Отправленное сообщение. Доступ под блокировкой this. */
    private @Nullable LoginRequestMessage message;

    LoginSession(AuthService service, Player player, Account account) {
        super(service, player);
        this.account = account;
        this.timeout = service.config().auth().loginTimeout();
    }

    @Override
    protected void start() {
        String id = service.issueLoginRequest(this);
        requestId = id;
        GameMessages messages = service.messages();
        TagResolver placeholders = TagResolver.resolver(
                Placeholder.unparsed("player", username()),
                Placeholder.unparsed("timeout", Long.toString(timeout.toSeconds()))
        );
        Texts.send(player, messages.login(), placeholders);
        Texts.showTitle(player, messages.loginTitle(), messages.loginSubtitle(), timeout, placeholders);
        schedule(this::timeout, timeout);

        service.discord().sendLoginRequest(account.discordId(), username(), ip, Instant.now(), id)
                .whenComplete((sent, error) -> {
                    if (error == null) {
                        attach(sent);
                    } else {
                        sendFailed(error);
                    }
                });
    }

    @Override
    protected void stop() {
        expire();
        service.releaseLoginRequest(requestId, this);
    }

    /**
     * Обрабатывает нажатие кнопки в Discord.
     */
    LoginResolution resolve(long discordUserId, boolean approve) {
        if (discordUserId != account.discordId()) {
            return LoginResolution.FOREIGN;
        }
        if (!state.compareAndSet(State.WAITING, approve ? State.APPROVED : State.DENIED)) {
            return LoginResolution.EXPIRED;
        }
        if (approve) {
            service.rememberLogin(account.username(), ip);
            execute(() -> {
                Texts.send(player, service.messages().loginApproved(), Placeholder.unparsed("player", username()));
                pass();
            });
            return LoginResolution.APPROVED;
        }
        execute(() -> kick(service.messages().kickLoginDenied()));
        return LoginResolution.DENIED;
    }

    private void timeout() {
        if (expire()) {
            kick(service.messages().kickLoginTimeout());
        }
    }

    /**
     * Завершает ожидающий запрос и помечает сообщение в Discord как недействительное.
     *
     * @return {@code true}, если запрос ещё ожидал ответа
     */
    private boolean expire() {
        if (!state.compareAndSet(State.WAITING, State.EXPIRED)) {
            return false;
        }
        LoginRequestMessage sent;
        synchronized (this) {
            sent = message;
        }
        if (sent != null) {
            sent.markExpired();
        }
        return true;
    }

    private synchronized void attach(LoginRequestMessage sent) {
        if (state.get() == State.EXPIRED) {
            // Игрок вышел или время истекло, пока сообщение отправлялось
            sent.markExpired();
        } else {
            message = sent;
        }
    }

    private void sendFailed(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String reason;
        if (cause instanceof DirectMessagesClosedException) {
            reason = service.messages().kickDirectMessagesClosed();
        } else {
            service.logger().error("Не удалось отправить запрос на вход {} в Discord", username(), cause);
            reason = service.messages().kickUnavailable();
        }
        execute(() -> {
            if (state.compareAndSet(State.WAITING, State.EXPIRED)) {
                kick(reason);
            }
        });
    }
}
