package su.prismaworld.logify.premium;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Проверяет через Mojang API, принадлежит ли ник лицензионному аккаунту.
 */
public final class MojangProfiles implements AutoCloseable {

    private static final String LOOKUP_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Cache<String, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(10_000)
            .build();

    /**
     * @return {@code true}, если ник принадлежит лицензионному аккаунту. Future завершается ошибкой,
     *     если Mojang API недоступен или ответил неожиданно.
     */
    public CompletableFuture<Boolean> isPremium(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        Boolean cached = cache.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(LOOKUP_URL + URLEncoder.encode(username, StandardCharsets.UTF_8)))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(response -> {
            boolean premium = switch (response.statusCode()) {
                case 200 -> true;
                case 204, 404 -> false;
                default -> throw new IllegalStateException("Mojang API ответил кодом " + response.statusCode());
            };
            cache.put(key, premium);
            return premium;
        });
    }

    @Override
    public void close() {
        client.close();
    }
}
