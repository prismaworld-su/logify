package su.prismaworld.logify.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoginButtonTest {

    @Test
    void roundTrips() {
        LoginButton approve = new LoginButton(true, "aB3_-xYz09Qw", "Steve_01");
        LoginButton deny = new LoginButton(false, "aB3_-xYz09Qw", "Steve_01");

        assertEquals(Optional.of(approve), LoginButton.parse(approve.id()));
        assertEquals(Optional.of(deny), LoginButton.parse(deny.id()));
        assertTrue(approve.id().length() <= 100, "Discord ограничивает ID кнопки 100 символами");
    }

    @Test
    void ignoresForeignButtons() {
        assertEquals(Optional.empty(), LoginButton.parse("other:button"));
        assertEquals(Optional.empty(), LoginButton.parse("logify:login:maybe:id:Steve"));
        assertEquals(Optional.empty(), LoginButton.parse("logify:login:approve:id"));
        assertEquals(Optional.empty(), LoginButton.parse("logify:login:approve::Steve"));
    }
}
