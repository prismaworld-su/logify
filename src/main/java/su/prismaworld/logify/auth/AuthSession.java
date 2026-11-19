package su.prismaworld.logify.auth;

import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Игрок в мире ожидания. Методы LimboAPI вызываются в потоке соединения игрока,
 * поэтому действия из других потоков (Discord, база) передаются туда через {@link #execute(Runnable)}.
 */
abstract class AuthSession implements LimboSessionHandler {

    protected final AuthService service;
    protected final Player player;
    protected final String ip;

    private final List<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();
    private volatile @Nullable LimboPlayer limboPlayer;

    AuthSession(AuthService service, Player player) {
        this.service = service;
        this.player = player;
        this.ip = AuthService.ip(player);
    }

    final String username() {
        return player.getUsername();
    }

    @Override
    public final void onSpawn(Limbo server, LimboPlayer limboPlayer) {
        this.limboPlayer = limboPlayer;
        if (!service.activate(this)) {
            player.disconnect(service.message(service.messages().kickAlreadyAuthenticating()));
            return;
        }
        limboPlayer.disableFalling();
        start();
    }

    @Override
    public final void onDisconnect() {
        tasks.forEach(task -> task.cancel(false));
        tasks.clear();
        if (service.deactivate(this)) {
            stop();
        }
    }

    /**
     * Игрок появился в мире ожидания.
     */
    protected abstract void start();

    /**
     * Игрок покинул мир ожидания: прошёл авторизацию, был отключён или вышел сам.
     */
    protected abstract void stop();

    protected final void execute(Runnable action) {
        LimboPlayer current = limboPlayer;
        if (current != null) {
            current.getScheduledExecutor().execute(action);
        }
    }

    protected final void schedule(Runnable action, Duration delay) {
        LimboPlayer current = limboPlayer;
        if (current != null) {
            tasks.add(current.getScheduledExecutor().schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    protected final void scheduleRepeating(Runnable action, Duration interval) {
        LimboPlayer current = limboPlayer;
        if (current != null && !interval.isZero()) {
            long millis = interval.toMillis();
            tasks.add(current.getScheduledExecutor().scheduleAtFixedRate(action, millis, millis, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * Выпускает игрока из мира ожидания на сервер. Вызывать в потоке соединения.
     */
    protected final void pass() {
        LimboPlayer current = limboPlayer;
        if (current != null) {
            player.clearTitle();
            current.disconnect();
        }
    }

    protected final void kick(String template) {
        player.disconnect(service.message(template));
    }

    protected final void kick(Component reason) {
        player.disconnect(reason);
    }
}
