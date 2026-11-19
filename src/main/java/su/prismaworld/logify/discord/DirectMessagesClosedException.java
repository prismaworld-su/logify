package su.prismaworld.logify.discord;

/**
 * Пользователь Discord запретил личные сообщения от бота.
 */
public final class DirectMessagesClosedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DirectMessagesClosedException(long userId, Throwable cause) {
        super("Пользователь Discord " + userId + " не принимает личные сообщения", cause);
    }
}
