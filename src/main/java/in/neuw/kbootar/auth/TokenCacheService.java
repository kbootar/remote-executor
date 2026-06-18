package in.neuw.kbootar.auth;

import in.neuw.kbootar.collection.Environment;
import in.neuw.kbootar.collection.OAuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenCacheService {

    private static final Logger log = LoggerFactory.getLogger(TokenCacheService.class);

    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final CredentialVault vault;
    private final WebClient webClient;

    public TokenCacheService(CredentialVault vault, WebClient webClient) {
        this.vault = vault;
        this.webClient = webClient;
    }

    public Mono<String> getToken(String envId, Environment environment) {
        CachedToken cached = cache.get(envId);
        if (cached != null && !cached.isExpired()) {
            return Mono.just(cached.accessToken());
        }
        return fetchAndCache(envId, environment);
    }

    private Mono<String> fetchAndCache(String envId, Environment environment) {
        OAuthConfig config = environment.oauthConfig();
        if (config == null) {
            return Mono.error(new IllegalStateException(
                    "No OAuth config for environment: " + envId));
        }

        String clientId = vault.decryptIfEncrypted(config.encryptedClientId());
        String clientSecret = vault.decryptIfEncrypted(config.encryptedClientSecret());

        var formData = BodyInserters
                .fromFormData("grant_type", "client_credentials")
                .with("client_id", clientId)
                .with("client_secret", clientSecret);

        if (config.scope() != null && !config.scope().isBlank()) {
            formData = formData.with("scope", config.scope());
        }

        return webClient.post()
                .uri(config.tokenUri())
                .body(formData)
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .map(response -> {
                    var token = new CachedToken(
                            response.accessToken(),
                            Instant.now().plusSeconds(response.expiresIn()));
                    cache.put(envId, token);
                    log.debug("Fetched and cached token for env={}", envId);
                    return response.accessToken();
                })
                .onErrorMap(e -> !(e instanceof TokenFetchException),
                        e -> new TokenFetchException(
                                "Token fetch failed for env: " + envId, e));
    }
}
