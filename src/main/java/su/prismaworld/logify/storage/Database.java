package su.prismaworld.logify.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import su.prismaworld.logify.config.LogifyConfig.DatabaseSettings;

/**
 * Пул соединений с MariaDB.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;

    public Database(DatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("logify-mariadb");
        // Имя класса берётся из ссылки на класс, чтобы оно оставалось верным после relocate в shadowJar
        config.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        config.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.name());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
        // Не падать при старте, если база ещё недоступна: пул подключится, когда она поднимется
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
