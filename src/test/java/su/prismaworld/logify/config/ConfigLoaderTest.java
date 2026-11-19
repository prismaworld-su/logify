package su.prismaworld.logify.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moandjiezana.toml.Toml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.player.GameMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    private static LogifyConfig parse(String toml) {
        return parse(toml, Map.of());
    }

    private static LogifyConfig parse(String toml, Map<String, String> environment) {
        return ConfigLoader.parse(new Toml().read(toml), ConfigLoader.readTemplate(), environment::get);
    }

    @Test
    void templateHasValidDefaults() {
        LogifyConfig config = parse("");

        assertEquals("mariadb", config.database().host());
        assertEquals(3306, config.database().port());
        assertEquals("logify_", config.database().tablePrefix());
        assertFalse(config.discord().isConfigured());
        assertEquals("/r", config.discord().command());
        assertEquals(Duration.ofMinutes(5), config.auth().registrationTimeout());
        assertEquals(Duration.ofHours(12), config.auth().sessionDuration());
        assertEquals(1, config.auth().maxAccountsPerDiscord());
        assertTrue(config.auth().nicknamePattern().matcher("Steve_01").matches());
        assertFalse(config.auth().nicknamePattern().matcher("Стив").matches());
        assertTrue(config.premium().skipOnlineMode());
        assertFalse(config.premium().autoDetect());
        assertEquals(Dimension.THE_END, config.limbo().dimension());
        assertEquals(GameMode.ADVENTURE, config.limbo().gameMode());
        assertTrue(config.messages().registration().contains("<command>"));
        assertTrue(config.discordMessages().loginRequest().contains("{player}"));
    }

    @Test
    void readsValuesAndKeepsDefaultsForMissingKeys() {
        LogifyConfig config = parse("""
                [discord]
                token = "secret"
                registration-channel-id = "123456789012345678"

                [auth]
                session-duration = 0
                """);

        assertTrue(config.discord().isConfigured());
        assertEquals(123456789012345678L, config.discord().registrationChannelId());
        assertEquals(Duration.ZERO, config.auth().sessionDuration());
        // Ключ отсутствует в файле - берётся из шаблона
        assertEquals(Duration.ofMinutes(2), config.auth().loginTimeout());
    }

    @Test
    void acceptsChannelIdAsNumber() {
        LogifyConfig config = parse("""
                [discord]
                registration-channel-id = 123456789012345678
                """);

        assertEquals(123456789012345678L, config.discord().registrationChannelId());
    }

    @Test
    void normalizesSlashCommandName() {
        assertEquals("r", parse("").discord().commandName());
        assertEquals("register", parse("[discord]\ncommand = \"register\"").discord().commandName());
        assertEquals("reg", parse("[discord]\ncommand = \"/Reg\"").discord().commandName());
        assertEquals("/reg", parse("[discord]\ncommand = \"/Reg\"").discord().command());
    }

    @Test
    void rejectsInvalidSlashCommands() {
        assertThrows(ConfigException.class, () -> parse("[discord]\ncommand = \"/\""));
        assertThrows(ConfigException.class, () -> parse("[discord]\ncommand = \"/r code\""));
        assertThrows(ConfigException.class, () -> parse("[discord]\ncommand = \"/r!\""));
        assertThrows(ConfigException.class, () -> parse("[discord]\ncommand = \"/" + "r".repeat(33) + "\""));
        assertThrows(ConfigException.class, () -> parse("[discord-messages]\ncommand-description = \"" + "a".repeat(101) + "\""));
        assertThrows(ConfigException.class, () -> parse("[discord-messages]\nlogin-approve-button = \"" + "a".repeat(81) + "\""));
    }

    @Test
    void environmentOverridesSecrets() {
        LogifyConfig config = parse("""
                [database]
                password = "from-file"
                [discord]
                token = "from-file"
                """, Map.of(ConfigLoader.DATABASE_PASSWORD_ENV, "from-env", ConfigLoader.DISCORD_TOKEN_ENV, "token-env"));

        assertEquals("from-env", config.database().password());
        assertEquals("token-env", config.discord().token());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(ConfigException.class, () -> parse("[database]\ntable-prefix = \"bad prefix\""));
        assertThrows(ConfigException.class, () -> parse("[database]\nport = 70000"));
        assertThrows(ConfigException.class, () -> parse("[database]\nport = \"3306\""));
        assertThrows(ConfigException.class, () -> parse("[discord]\nregistration-channel-id = \"abc\""));
        assertThrows(ConfigException.class, () -> parse("[discord]\ncommand = \"/r code\""));
        assertThrows(ConfigException.class, () -> parse("[auth]\nnickname-pattern = \"[\""));
        assertThrows(ConfigException.class, () -> parse("[limbo]\ndimension = \"MOON\""));
    }

    @Test
    void createsFileFromTemplate(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("logify");

        ConfigLoader.Result first = ConfigLoader.load(dataDirectory);
        ConfigLoader.Result second = ConfigLoader.load(dataDirectory);

        assertTrue(first.created());
        assertFalse(second.created());
        String content = Files.readString(dataDirectory.resolve(ConfigLoader.FILE_NAME), StandardCharsets.UTF_8);
        assertTrue(content.contains("[discord]"));
    }

    @Test
    void reportsSyntaxErrors(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve(ConfigLoader.FILE_NAME), "[database\nhost = ", StandardCharsets.UTF_8);

        assertThrows(ConfigException.class, () -> ConfigLoader.load(directory));
    }
}
