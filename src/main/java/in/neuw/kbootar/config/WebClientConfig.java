package in.neuw.kbootar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
class WebClientConfig {

    // Used by TokenCacheService for OAuth2 token fetching.
    // RequestExecutor builds its own per-request WebClient to apply RequestSettings.
    @Bean
    WebClient webClient() {
        return WebClient.builder().build();
    }
}
