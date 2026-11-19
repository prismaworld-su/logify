package su.prismaworld.logify.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.proxy.Player;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import su.prismaworld.logify.auth.AuthAction;
import su.prismaworld.logify.auth.AuthService;
import su.prismaworld.logify.config.LogifyConfig;
import su.prismaworld.logify.premium.MojangProfiles;
import su.prismaworld.logify.text.Texts;

/**
 * Проверяет игроков при входе на прокси и отправляет их в мир ожидания.
 */
public final class ConnectionListener {

    private final LogifyConfig config;
    private final AuthService authService;
    private final @Nullable MojangProfiles mojang;
    private final Logger logger;

    /**
     * @param mojang проверка лицензии, {@code null}, если premium.auto-detect выключен
     */
    public ConnectionListener(LogifyConfig config, AuthService authService, @Nullable MojangProfiles mojang, Logger logger) {
        this.config = config;
        this.authService = authService;
        this.mojang = mojang;
        this.logger = logger;
    }

    @Subscribe
    public @Nullable EventTask onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        String username = event.getUsername();
        if (!config.auth().nicknamePattern().matcher(username).matches()) {
            event.setResult(PreLoginComponentResult.denied(Texts.render(config.messages().kickInvalidNickname())));
            return null;
        }
        MojangProfiles profiles = mojang;
        if (profiles == null || event.getResult().isForceOfflineMode() || event.getResult().isOnlineModeAllowed()) {
            return null;
        }
        // Проверка Mojang включается только для свободных ников: зарегистрированные через Discord
        // принадлежат игрокам, которые могут играть без лицензии
        CompletableFuture<Void> check = authService.findAccount(username)
                .thenCompose(account -> account.isPresent()
                        ? CompletableFuture.completedFuture(false)
                        : profiles.isPremium(username))
                .handle((premium, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        logger.warn("Не удалось проверить лицензию ника {}: {}", username, cause.toString());
                    } else if (premium) {
                        event.setResult(PreLoginComponentResult.forceOnlineMode());
                    }
                    return null;
                });
        return EventTask.resumeWhenComplete(check);
    }

    @Subscribe
    public @Nullable EventTask onLoginLimboRegister(LoginLimboRegisterEvent event) {
        Player player = event.getPlayer();
        if (config.premium().skipOnlineMode() && player.isOnlineMode()) {
            return null;
        }
        CompletableFuture<Void> decision = authService.decide(player).thenAccept(action -> {
            if (!(action instanceof AuthAction.Allow)) {
                event.addOnJoinCallback(() -> authService.start(player, action));
            }
        });
        return EventTask.resumeWhenComplete(decision);
    }
}
