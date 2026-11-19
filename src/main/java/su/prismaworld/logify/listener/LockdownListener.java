package su.prismaworld.logify.listener;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Не пускает никого на сервер, если плагин не смог запуститься: без авторизации любой мог бы зайти под чужим ником.
 */
public final class LockdownListener {

    private static final Component REASON = Component.text(
            "Авторизация на сервере не настроена. Сообщите администрации.", NamedTextColor.RED);

    @Subscribe
    public void onLogin(LoginEvent event) {
        event.setResult(ComponentResult.denied(REASON));
    }
}
