package su.prismaworld.logify.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Таблица аккаунтов в MariaDB. Все методы блокирующие: вызывать только вне потоков сети и Discord.
 */
public final class AccountRepository {

    public enum CreateResult {
        CREATED,
        /** Ник уже зарегистрирован. */
        NAME_TAKEN,
        /** К пользователю Discord привязано максимальное число аккаунтов. */
        LIMIT_REACHED
    }

    private final Database database;
    private final String table;
    private volatile boolean schemaReady;

    public AccountRepository(Database database, String tablePrefix) {
        this.database = database;
        this.table = "`" + tablePrefix + "accounts`";
    }

    /**
     * Создаёт таблицу, если её нет. Повторяется при следующем запросе, если база была недоступна.
     */
    public void ensureSchema() throws SQLException {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            try (Connection connection = database.connection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                        + "`username_key` VARCHAR(16) NOT NULL,"
                        + "`username` VARCHAR(16) NOT NULL,"
                        + "`discord_id` BIGINT NOT NULL,"
                        + "`registered_at` BIGINT NOT NULL,"
                        + "`last_login_ip` VARCHAR(45) NULL,"
                        + "`last_login_at` BIGINT NULL,"
                        + "PRIMARY KEY (`username_key`),"
                        + "INDEX `discord_id` (`discord_id`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
            }
            schemaReady = true;
        }
    }

    public Optional<Account> find(String username) throws SQLException {
        ensureSchema();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT `username`, `discord_id`, `registered_at`, `last_login_ip`, `last_login_at` FROM " + table
                             + " WHERE `username_key` = ?")) {
            statement.setString(1, key(username));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public List<Account> findByDiscord(long discordId) throws SQLException {
        ensureSchema();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT `username`, `discord_id`, `registered_at`, `last_login_ip`, `last_login_at` FROM " + table
                             + " WHERE `discord_id` = ? ORDER BY `registered_at`")) {
            statement.setLong(1, discordId);
            try (ResultSet result = statement.executeQuery()) {
                List<Account> accounts = new ArrayList<>();
                while (result.next()) {
                    accounts.add(read(result));
                }
                return accounts;
            }
        }
    }

    /**
     * Регистрирует аккаунт, если ник свободен и у пользователя Discord не превышен лимит.
     * Проверка лимита и вставка не атомарны между собой, поэтому вызовы должны идти из одного потока.
     */
    public CreateResult create(Account account, int maxAccountsPerDiscord) throws SQLException {
        ensureSchema();
        try (Connection connection = database.connection()) {
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE `discord_id` = ?")) {
                count.setLong(1, account.discordId());
                try (ResultSet result = count.executeQuery()) {
                    result.next();
                    if (result.getInt(1) >= maxAccountsPerDiscord) {
                        return CreateResult.LIMIT_REACHED;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table
                    + " (`username_key`, `username`, `discord_id`, `registered_at`, `last_login_ip`, `last_login_at`)"
                    + " VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, key(account.username()));
                insert.setString(2, account.username());
                insert.setLong(3, account.discordId());
                insert.setLong(4, account.registeredAt().toEpochMilli());
                setNullableString(insert, 5, account.lastLoginIp());
                setNullableInstant(insert, 6, account.lastLoginAt());
                insert.executeUpdate();
                return CreateResult.CREATED;
            } catch (SQLIntegrityConstraintViolationException e) {
                return CreateResult.NAME_TAKEN;
            }
        }
    }

    public void updateLastLogin(String username, String ip, Instant at) throws SQLException {
        ensureSchema();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + table + " SET `last_login_ip` = ?, `last_login_at` = ? WHERE `username_key` = ?")) {
            statement.setString(1, ip);
            statement.setLong(2, at.toEpochMilli());
            statement.setString(3, key(username));
            statement.executeUpdate();
        }
    }

    /**
     * Сбрасывает сессию, чтобы следующий вход потребовал подтверждения в Discord.
     */
    public boolean clearSession(String username) throws SQLException {
        ensureSchema();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + table + " SET `last_login_ip` = NULL, `last_login_at` = NULL WHERE `username_key` = ?")) {
            statement.setString(1, key(username));
            return statement.executeUpdate() > 0;
        }
    }

    public boolean delete(String username) throws SQLException {
        ensureSchema();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE `username_key` = ?")) {
            statement.setString(1, key(username));
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Ники в Minecraft не различают регистр при сравнении, поэтому ключ хранится в нижнем регистре.
     */
    static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static Account read(ResultSet result) throws SQLException {
        long lastLoginAt = result.getLong("last_login_at");
        boolean hasLastLogin = !result.wasNull();
        return new Account(
                result.getString("username"),
                result.getLong("discord_id"),
                Instant.ofEpochMilli(result.getLong("registered_at")),
                result.getString("last_login_ip"),
                hasLastLogin ? Instant.ofEpochMilli(lastLoginAt) : null
        );
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }
}
