package su.prismaworld.logify.text;

import java.time.Duration;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;

/**
 * Оформление сообщений: MiniMessage для игры и простые плейсхолдеры {@code {name}} для Discord.
 */
public final class Texts {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Duration TITLE_FADE_IN = Duration.ofMillis(250);
    private static final Duration TITLE_FADE_OUT = Duration.ofMillis(500);

    private Texts() {
    }

    public static Component render(String template, TagResolver... resolvers) {
        return template.isBlank() ? Component.empty() : MINI_MESSAGE.deserialize(template, resolvers);
    }

    /**
     * Отправляет сообщение, если шаблон не пустой.
     */
    public static void send(Audience audience, String template, TagResolver... resolvers) {
        if (!template.isBlank()) {
            audience.sendMessage(MINI_MESSAGE.deserialize(template, resolvers));
        }
    }

    /**
     * Показывает заголовок на время {@code stay}, если заголовок или подзаголовок не пустые.
     */
    public static void showTitle(Audience audience, String title, String subtitle, Duration stay, TagResolver... resolvers) {
        if (title.isBlank() && subtitle.isBlank()) {
            return;
        }
        audience.showTitle(Title.title(
                render(title, resolvers),
                render(subtitle, resolvers),
                Title.Times.times(TITLE_FADE_IN, stay, TITLE_FADE_OUT)
        ));
    }

    /**
     * Подставляет значения вместо {@code {key}}. Значения вставляются как есть, экранирование - на вызывающей стороне.
     */
    public static String format(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
