package su.prismaworld.logify.config;

/**
 * Ошибка в {@code config.toml}. Сообщение предназначено для администратора сервера.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
