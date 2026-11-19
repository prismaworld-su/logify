package su.prismaworld.logify;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import net.elytrium.limboapi.api.LimboFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import su.prismaworld.logify.auth.AuthService;
import su.prismaworld.logify.command.LogifyCommand;
import su.prismaworld.logify.config.ConfigException;
import su.prismaworld.logify.config.ConfigLoader;
import su.prismaworld.logify.config.LogifyConfig;
import su.prismaworld.logify.discord.DiscordBot;
import su.prismaworld.logify.discord.DiscordListener;
import su.prismaworld.logify.limbo.AuthLimbo;
import su.prismaworld.logify.listener.ConnectionListener;
import su.prismaworld.logify.listener.LockdownListener;
import su.prismaworld.logify.premium.MojangProfiles;
import su.prismaworld.logify.storage.AccountRepository;
import su.prismaworld.logify.storage.Database;

@Plugin(
        id = "logify",
        name = "Logify",
        version = BuildConstants.VERSION,
        description = "Discord login for Velocity: link nicknames to Discord and approve every login from a direct message",
        authors = {"loseex"},
        dependencies = {@Dependency(id = "limboapi")}
)
public final class Logify {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private @Nullable Database database;
    private @Nullable DiscordBot discordBot;
    private @Nullable AuthService authService;
    private @Nullable AuthLimbo limbo;
    private @Nullable MojangProfiles mojang;
    private @Nullable ConnectionListener connectionListener;

    @Inject
    public Logify(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            enable();
        } catch (Exception e) {
            if (e instanceof ConfigException) {
                logger.error("Ошибка в {}: {}", ConfigLoader.FILE_NAME, e.getMessage());
            } else {
                logger.error("Не удалось запустить Logify", e);
            }
            logger.error("Вход на сервер закрыт, пока Logify не запустится");
            if (connectionListener != null) {
                proxy.getEventManager().unregisterListener(this, connectionListener);
                connectionListener = null;
            }
            proxy.getCommandManager().unregister("logify");
            shutdown();
            proxy.getEventManager().register(this, new LockdownListener());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shutdown();
    }

    private void enable() throws Exception {
        ConfigLoader.Result loaded = ConfigLoader.load(dataDirectory);
        LogifyConfig config = loaded.config();
        if (loaded.created()) {
            logger.warn("Создан {}. Укажите в нём подключение к базе и данные Discord-бота и перезапустите прокси",
                    dataDirectory.resolve(ConfigLoader.FILE_NAME));
        }

        LimboFactory limboFactory = proxy.getPluginManager().getPlugin("limboapi")
                .flatMap(PluginContainer::getInstance)
                .map(LimboFactory.class::cast)
                .orElseThrow(() -> new IllegalStateException("LimboAPI не найден"));

        database = new Database(config.database());
        AccountRepository accounts = new AccountRepository(database, config.database().tablePrefix());
        limbo = new AuthLimbo(limboFactory, config.limbo());
        discordBot = new DiscordBot(config.discord(), config.discordMessages(), logger);
        authService = new AuthService(config, accounts, discordBot, limbo, logger);
        if (config.premium().autoDetect()) {
            mojang = new MojangProfiles();
        }

        connectionListener = new ConnectionListener(config, authService, mojang, logger);
        proxy.getEventManager().register(this, connectionListener);
        CommandMeta meta = proxy.getCommandManager().metaBuilder("logify").plugin(this).build();
        proxy.getCommandManager().register(meta, new LogifyCommand(proxy, authService, logger));

        discordBot.start(new DiscordListener(discordBot, authService, config, logger));
        // Таблица создаётся сразу, чтобы ошибки подключения к базе были видны при запуске
        proxy.getScheduler().buildTask(this, () -> {
            try {
                accounts.ensureSchema();
            } catch (Exception e) {
                logger.error("Не удалось подключиться к базе данных. Проверьте раздел [database] в {}", ConfigLoader.FILE_NAME, e);
            }
        }).schedule();
    }

    private void shutdown() {
        if (discordBot != null) {
            discordBot.shutdown();
            discordBot = null;
        }
        if (authService != null) {
            authService.shutdown();
            authService = null;
        }
        if (limbo != null) {
            limbo.dispose();
            limbo = null;
        }
        if (mojang != null) {
            mojang.close();
            mojang = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
