package su.prismaworld.logify.auth;

/**
 * Результат нажатия кнопки в запросе на вход.
 */
public enum LoginResolution {
    /** Вход подтверждён. */
    APPROVED,
    /** Вход отклонён. */
    DENIED,
    /** Запрос уже завершён или игрок вышел. */
    EXPIRED,
    /** Кнопку нажал не владелец аккаунта. */
    FOREIGN
}
