package su.prismaworld.logify.config;

import com.moandjiezana.toml.Toml;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.player.GameMode;
import su.prismaworld.logify.config.LogifyConfig.AuthSettings;
import su.prismaworld.logify.config.LogifyConfig.DatabaseSettings;
import su.prismaworld.logify.config.LogifyConfig.DiscordMessages;
import su.prismaworld.logify.config.LogifyConfig.DiscordSettings;
import su.prismaworld.logify.config.LogifyConfig.GameMessages;
import su.prismaworld.logify.config.LogifyConfig.LimboSettings;
import su.prismaworld.logify.config.LogifyConfig.PremiumSettings;

/**
 * Читает {@code config.toml}. Если файла нет, создаёт его из шаблона в ресурсах плагина.
 * Ключи, которых нет в файле (например, добавленные в новой версии), берутся из шаблона.
 */
public final class ConfigLoader {

    public static final String FILE_NAME = "config.toml";
    public static final String DATABASE_PASSWORD_ENV = "LOGIFY_DATABASE_PASSWORD";
    public static final String DISCORD_TOKEN_ENV = "LOGIFY_DISCORD_TOKEN";

    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");
    /** Имя slash-команды по правилам Discord. */
    private static final Pattern COMMAND_NAME = Pattern.compile("[-_\\p{L}\\p{N}]{1,32}");
    /** Ограничения Discord на длину описаний команд и подписей кнопок. */
    private static final int MAX_DESCRIPTION_LENGTH = 100;
    private static final int MAX_BUTTON_LABEL_LENGTH = 80;

    private final Toml toml;
    private final Toml template;
    private final UnaryOperator<String> environment;

    private ConfigLoader(Toml toml, Toml template, UnaryOperator<String> environment) {
        this.toml = toml;
        this.template = template;
        this.environment = environment;
    }

    /**
     * @param config настройки
     * @param created {@code true}, если файл только что создан из шаблона
     */
    public record Result(LogifyConfig config, boolean created) {
    }

    public static Result load(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(FILE_NAME);
        boolean created = false;
        if (Files.notExists(file)) {
            Files.createDirectories(dataDirectory);
            try (InputStream template = openTemplate()) {
                Files.copy(template, file);
            }
            created = true;
        }

        Toml toml;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            toml = new Toml().read(reader);
        } catch (IllegalStateException e) {
            // toml4j сообщает о синтаксических ошибках через IllegalStateException
            throw new ConfigException("Ошибка синтаксиса в " + FILE_NAME + ": " + e.getMessage(), e);
        }
        return new Result(parse(toml, readTemplate(), System::getenv), created);
    }

    static LogifyConfig parse(Toml toml, Toml template, UnaryOperator<String> environment) {
        return new ConfigLoader(toml, template, environment).parse();
    }

    static Toml readTemplate() {
        try (InputStream template = openTemplate();
             Reader reader = new InputStreamReader(template, StandardCharsets.UTF_8)) {
            return new Toml().read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать встроенный " + FILE_NAME, e);
        }
    }

    private static InputStream openTemplate() throws IOException {
        InputStream stream = ConfigLoader.class.getClassLoader().getResourceAsStream(FILE_NAME);
        if (stream == null) {
            throw new IOException("В jar плагина нет шаблона " + FILE_NAME);
        }
        return stream;
    }

    private LogifyConfig parse() {
        return new LogifyConfig(database(), discord(), auth(), premium(), limbo(), messages(), discordMessages());
    }

    private DatabaseSettings database() {
        String prefix = string("database.table-prefix");
        if (!TABLE_PREFIX.matcher(prefix).matches()) {
            throw new ConfigException("database.table-prefix может содержать только латинские буквы, цифры и \"_\" (до 32 символов)");
        }
        return new DatabaseSettings(
                requireNotBlank("database.host"),
                intInRange("database.port", 1, 65535),
                requireNotBlank("database.name"),
                requireNotBlank("database.username"),
                withEnvironment(DATABASE_PASSWORD_ENV, string("database.password")),
                prefix,
                intInRange("database.pool-size", 1, 64)
        );
    }

    private DiscordSettings discord() {
        String command = string("discord.command").strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        // Discord принимает имена команд только в нижнем регистре
        command = command.toLowerCase(Locale.ROOT);
        if (!COMMAND_NAME.matcher(command).matches()) {
            throw new ConfigException("discord.command должна состоять из букв, цифр, \"-\" и \"_\" (до 32 символов), например \"/r\"");
        }
        return new DiscordSettings(
                withEnvironment(DISCORD_TOKEN_ENV, string("discord.token")).strip(),
                snowflake("discord.registration-channel-id"),
                command,
                string("discord.activity").strip()
        );
    }

    private AuthSettings auth() {
        String patternSource = string("auth.nickname-pattern");
        Pattern nicknamePattern;
        try {
            nicknamePattern = Pattern.compile(patternSource);
        } catch (PatternSyntaxException e) {
            throw new ConfigException("auth.nickname-pattern не является корректным регулярным выражением: " + e.getDescription(), e);
        }
        return new AuthSettings(
                Duration.ofSeconds(longInRange("auth.registration-timeout", 10, 86_400)),
                Duration.ofSeconds(longInRange("auth.login-timeout", 10, 86_400)),
                Duration.ofMinutes(longInRange("auth.session-duration", 0, 525_600)),
                intInRange("auth.max-accounts-per-discord", 1, 100),
                Duration.ofSeconds(longInRange("auth.reminder-interval", 0, 86_400)),
                nicknamePattern,
                intInRange("auth.attempts-per-minute", 1, 1000)
        );
    }

    private PremiumSettings premium() {
        return new PremiumSettings(bool("premium.skip-online-mode"), bool("premium.auto-detect"));
    }

    private LimboSettings limbo() {
        return new LimboSettings(
                enumValue("limbo.dimension", Dimension.class),
                enumValue("limbo.game-mode", GameMode.class)
        );
    }

    private GameMessages messages() {
        return new GameMessages(
                string("messages.registration"),
                string("messages.command-hover"),
                string("messages.registration-title"),
                string("messages.registration-subtitle"),
                string("messages.registered"),
                string("messages.login"),
                string("messages.login-title"),
                string("messages.login-subtitle"),
                string("messages.login-approved"),
                string("messages.kick-login-denied"),
                string("messages.kick-registration-timeout"),
                string("messages.kick-login-timeout"),
                string("messages.kick-direct-messages-closed"),
                string("messages.kick-unavailable"),
                string("messages.kick-invalid-nickname"),
                string("messages.kick-wrong-case"),
                string("messages.kick-already-authenticating"),
                string("messages.kick-name-taken")
        );
    }

    private DiscordMessages discordMessages() {
        return new DiscordMessages(
                limited("discord-messages.command-description", MAX_DESCRIPTION_LENGTH),
                limited("discord-messages.command-code-description", MAX_DESCRIPTION_LENGTH),
                requireNotBlank("discord-messages.registration-wrong-channel"),
                requireNotBlank("discord-messages.registration-success"),
                requireNotBlank("discord-messages.registration-invalid-code"),
                requireNotBlank("discord-messages.registration-usage"),
                requireNotBlank("discord-messages.registration-limit"),
                requireNotBlank("discord-messages.registration-already-registered"),
                requireNotBlank("discord-messages.registration-rate-limited"),
                requireNotBlank("discord-messages.registration-error"),
                requireNotBlank("discord-messages.login-request-title"),
                requireNotBlank("discord-messages.login-request"),
                limited("discord-messages.login-approve-button", MAX_BUTTON_LABEL_LENGTH),
                limited("discord-messages.login-deny-button", MAX_BUTTON_LABEL_LENGTH),
                requireNotBlank("discord-messages.login-approved"),
                requireNotBlank("discord-messages.login-denied"),
                requireNotBlank("discord-messages.login-expired")
        );
    }

    private String withEnvironment(String variable, String fileValue) {
        String value = environment.apply(variable);
        return value == null || value.isBlank() ? fileValue : value;
    }

    private String string(String key) {
        Object value = value(key);
        if (!(value instanceof String string)) {
            throw typeError(key, "строкой");
        }
        return string;
    }

    private String requireNotBlank(String key) {
        String value = string(key);
        if (value.isBlank()) {
            throw new ConfigException(key + " не может быть пустым");
        }
        return value;
    }

    private String limited(String key, int maxLength) {
        String value = requireNotBlank(key);
        if (value.length() > maxLength) {
            throw new ConfigException(key + " должен быть не длиннее " + maxLength + " символов");
        }
        return value;
    }

    private boolean bool(String key) {
        Object value = value(key);
        if (!(value instanceof Boolean bool)) {
            throw typeError(key, "true или false");
        }
        return bool;
    }

    private long longInRange(String key, long min, long max) {
        Object value = value(key);
        if (!(value instanceof Long number)) {
            throw typeError(key, "целым числом");
        }
        if (number < min || number > max) {
            throw new ConfigException(key + " должен быть от " + min + " до " + max + ", сейчас " + number);
        }
        return number;
    }

    private int intInRange(String key, int min, int max) {
        return (int) longInRange(key, min, max);
    }

    /**
     * ID Discord принимается и строкой, и числом. Пустая строка означает, что ID не задан.
     */
    private long snowflake(String key) {
        Object value = value(key);
        if (value instanceof Long number && number >= 0) {
            return number;
        }
        if (value instanceof String string) {
            String trimmed = string.strip();
            if (trimmed.isEmpty()) {
                return 0;
            }
            try {
                long id = Long.parseUnsignedLong(trimmed);
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // сообщение об ошибке ниже
            }
        }
        throw new ConfigException(key + " должен быть ID Discord, например \"123456789012345678\"");
    }

    private <E extends Enum<E>> E enumValue(String key, Class<E> type) {
        String name = string(key).strip().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(key + ": неизвестное значение \"" + name + "\"", e);
        }
    }

    private Object value(String key) {
        Object value = rawValue(toml, key);
        if (value == null) {
            // Ключа нет в файле: берём значение из шаблона
            value = rawValue(template, key);
        }
        if (value == null) {
            throw new ConfigException("В " + FILE_NAME + " нет ключа " + key);
        }
        return value;
    }

    /**
     * Значение достаётся как Object и проверяется вызывающим методом: getString и подобные
     * методы toml4j бросают ClassCastException, если тип не совпадает.
     */
    private static Object rawValue(Toml source, String key) {
        int dot = key.lastIndexOf('.');
        Toml table = source.getTable(key.substring(0, dot));
        return table == null ? null : table.toMap().get(key.substring(dot + 1));
    }

    private static ConfigException typeError(String key, String expected) {
        return new ConfigException(key + " должен быть " + expected);
    }
}
