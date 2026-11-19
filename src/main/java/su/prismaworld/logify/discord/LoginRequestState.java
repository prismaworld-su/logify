package su.prismaworld.logify.discord;

import java.awt.Color;

/**
 * Состояние запроса на вход, которое отображается в личном сообщении.
 */
public enum LoginRequestState {
    PENDING(new Color(0x5865F2)),
    APPROVED(new Color(0x57F287)),
    DENIED(new Color(0xED4245)),
    EXPIRED(new Color(0x80848E));

    private final Color color;

    LoginRequestState(Color color) {
        this.color = color;
    }

    public Color color() {
        return color;
    }
}
