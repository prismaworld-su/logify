package su.prismaworld.logify.auth;

import net.kyori.adventure.text.Component;
import su.prismaworld.logify.storage.Account;

/**
 * Что сделать с игроком при входе на прокси.
 */
public sealed interface AuthAction {

    /** Пустить без проверки: действует сессия. */
    record Allow() implements AuthAction {
    }

    /** Ник свободен: зарегистрировать через Discord. */
    record Register() implements AuthAction {
    }

    /** Ник зарегистрирован: подтвердить вход в Discord. */
    record Login(Account account) implements AuthAction {
    }

    /** Не пускать. */
    record Deny(Component reason) implements AuthAction {
    }
}
