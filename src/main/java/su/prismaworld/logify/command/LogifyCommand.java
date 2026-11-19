package su.prismaworld.logify.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import su.prismaworld.logify.auth.AuthService;
import su.prismaworld.logify.storage.Account;
import su.prismaworld.logify.text.Texts;

/**
 * {@code /logify}: просмотр и сброс привязок для администраторов.
 */
public final class LogifyCommand implements SimpleCommand {

    public static final String PERMISSION = "logify.admin";

    private static final List<String> SUBCOMMANDS = List.of("info", "discord", "unregister", "reset-session");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ProxyServer proxy;
    private final AuthService authService;
    private final Logger logger;

    public LogifyCommand(ProxyServer proxy, AuthService authService, Logger logger) {
        this.proxy = proxy;
        this.authService = authService;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length != 2) {
            usage(source);
            return;
        }
        String target = args[1];
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> run(source, authService.findAccount(target), account -> {
                if (account.isEmpty()) {
                    send(source, "<red>Ник <white><name></white> не зарегистрирован.", Placeholder.unparsed("name", target));
                } else {
                    describe(source, account.get());
                }
            });
            case "discord" -> {
                long discordId;
                try {
                    discordId = Long.parseUnsignedLong(target);
                } catch (NumberFormatException e) {
                    send(source, "<red>Укажите числовой ID пользователя Discord.");
                    return;
                }
                run(source, authService.findAccounts(discordId), accounts -> {
                    if (accounts.isEmpty()) {
                        send(source, "<red>К Discord <white><id></white> не привязан ни один аккаунт.", Placeholder.unparsed("id", target));
                    } else {
                        accounts.forEach(account -> describe(source, account));
                    }
                });
            }
            case "unregister" -> run(source, authService.unregister(target), deleted -> {
                if (deleted) {
                    logger.info("{} удалил регистрацию {}", sourceName(source), target);
                    send(source, "<green>Регистрация <white><name></white> удалена.", Placeholder.unparsed("name", target));
                } else {
                    send(source, "<red>Ник <white><name></white> не зарегистрирован.", Placeholder.unparsed("name", target));
                }
            });
            case "reset-session" -> run(source, authService.resetSession(target), reset -> {
                if (reset) {
                    send(source, "<green>Сессия <white><name></white> сброшена: следующий вход потребует подтверждения.",
                            Placeholder.unparsed("name", target));
                } else {
                    send(source, "<red>Ник <white><name></white> не зарегистрирован.", Placeholder.unparsed("name", target));
                }
            });
            default -> usage(source);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(prefix)).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("discord")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    private <T> void run(CommandSource source, CompletableFuture<T> future, Consumer<T> handler) {
        future.thenAccept(handler).exceptionally(error -> {
            logger.error("Ошибка команды /logify", error);
            send(source, "<red>Не удалось выполнить команду: база данных недоступна.");
            return null;
        });
    }

    private void describe(CommandSource source, Account account) {
        // ID состоит только из цифр, поэтому его можно вставить прямо в аргумент тега click
        String discordId = Long.toUnsignedString(account.discordId());
        send(source, "<gray>Ник: <white><name></white><newline>"
                        + "Discord ID: <white><click:copy_to_clipboard:'" + discordId + "'><id></click></white><newline>"
                        + "Зарегистрирован: <white><registered></white><newline>"
                        + "Последний подтверждённый вход: <white><last_login></white>",
                Placeholder.unparsed("name", account.username()),
                Placeholder.unparsed("id", discordId),
                Placeholder.unparsed("registered", DATE_FORMAT.format(account.registeredAt())),
                Placeholder.unparsed("last_login", lastLogin(account)));
    }

    private static String lastLogin(Account account) {
        Instant at = account.lastLoginAt();
        if (at == null) {
            return "нет";
        }
        return DATE_FORMAT.format(at) + (account.lastLoginIp() == null ? "" : " с " + account.lastLoginIp());
    }

    private static void usage(CommandSource source) {
        send(source, "<gray>Использование:<newline>"
                + "<white>/logify info \\<ник></white> - привязка аккаунта<newline>"
                + "<white>/logify discord \\<id></white> - аккаунты пользователя Discord<newline>"
                + "<white>/logify unregister \\<ник></white> - удалить регистрацию<newline>"
                + "<white>/logify reset-session \\<ник></white> - требовать подтверждение при следующем входе");
    }

    private static String sourceName(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "Консоль";
    }

    private static void send(CommandSource source, String template, TagResolver... resolvers) {
        Texts.send(source, template, resolvers);
    }
}
