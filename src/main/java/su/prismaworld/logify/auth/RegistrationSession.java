package su.prismaworld.logify.auth;

import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;
import su.prismaworld.logify.config.LogifyConfig.GameMessages;
import su.prismaworld.logify.text.Texts;

/**
 * Регистрация: игрок получает код и ждёт, пока отправит команду с ним в канал Discord.
 */
final class RegistrationSession extends AuthSession {

    private enum State {
        /** Ждём команду из Discord. */
        WAITING,
        /** Команда получена, аккаунт сохраняется в базу. */
        PROCESSING,
        /** Игрок прошёл регистрацию, отключён или вышел. */
        DONE
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
    private final Duration timeout;
    private volatile @Nullable String code;
    private volatile Instant deadline = Instant.MAX;

    RegistrationSession(AuthService service, Player player) {
        super(service, player);
        this.timeout = service.config().auth().registrationTimeout();
    }

    @Override
    protected void start() {
        deadline = Instant.now().plus(timeout);
        code = service.issueRegistrationCode(this);
        GameMessages messages = service.messages();
        TagResolver placeholders = placeholders();
        Texts.send(player, messages.registration(), placeholders);
        Texts.showTitle(player, messages.registrationTitle(), messages.registrationSubtitle(), timeout, placeholders);
        scheduleRepeating(this::remind, service.config().auth().reminderInterval());
        schedule(this::timeout, timeout);
    }

    @Override
    protected void stop() {
        state.set(State.DONE);
        service.releaseRegistrationCode(code, this);
    }

    /**
     * Начинает обработку команды из Discord.
     *
     * @return {@code false}, если регистрация уже завершена или обрабатывается другая команда
     */
    boolean tryBeginProcessing() {
        return state.compareAndSet(State.WAITING, State.PROCESSING);
    }

    /**
     * Аккаунт не сохранён (например, превышен лимит): игрок продолжает ждать, пока не выйдет время.
     */
    void abortProcessing() {
        if (state.compareAndSet(State.PROCESSING, State.WAITING) && Instant.now().isAfter(deadline)) {
            execute(this::timeout);
        }
    }

    void completeRegistered() {
        execute(() -> {
            if (state.compareAndSet(State.PROCESSING, State.DONE)) {
                Texts.send(player, service.messages().registered(), placeholders());
                pass();
            }
        });
    }

    void failNameTaken() {
        execute(() -> {
            if (state.compareAndSet(State.PROCESSING, State.DONE)) {
                kick(service.messages().kickNameTaken());
            }
        });
    }

    private void remind() {
        if (state.get() == State.WAITING) {
            Texts.send(player, service.messages().registration(), placeholders());
        }
    }

    private void timeout() {
        if (state.compareAndSet(State.WAITING, State.DONE)) {
            kick(service.messages().kickRegistrationTimeout());
        }
    }

    private TagResolver placeholders() {
        String command = service.discord().command() + " " + code;
        Component commandComponent = Component.text(command).clickEvent(ClickEvent.copyToClipboard(command));
        String hover = service.messages().commandHover();
        if (!hover.isBlank()) {
            commandComponent = commandComponent.hoverEvent(HoverEvent.showText(Texts.render(hover)));
        }
        return TagResolver.resolver(
                Placeholder.unparsed("player", username()),
                Placeholder.component("command", commandComponent),
                Placeholder.unparsed("channel", service.discord().registrationChannelLabel()),
                Placeholder.unparsed("timeout", Long.toString(timeout.toSeconds()))
        );
    }
}
