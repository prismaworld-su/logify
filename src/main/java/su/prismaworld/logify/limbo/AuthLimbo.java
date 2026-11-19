package su.prismaworld.logify.limbo;

import com.velocitypowered.api.proxy.Player;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import su.prismaworld.logify.config.LogifyConfig.LimboSettings;

/**
 * Пустой виртуальный мир, в котором игрок ждёт авторизации. Игрок не подключается к серверам, пока не пройдёт её.
 */
public final class AuthLimbo {

    private static final long NOON = 6000;

    private final Limbo limbo;

    public AuthLimbo(LimboFactory factory, LimboSettings settings) {
        VirtualWorld world = factory.createVirtualWorld(settings.dimension(), 0, 100, 0, 0, 0);
        this.limbo = factory.createLimbo(world)
                .setName("Logify")
                .setWorldTime(NOON)
                .setGameMode(settings.gameMode());
    }

    public void spawn(Player player, LimboSessionHandler handler) {
        limbo.spawnPlayer(player, handler);
    }

    public void dispose() {
        limbo.dispose();
    }
}
