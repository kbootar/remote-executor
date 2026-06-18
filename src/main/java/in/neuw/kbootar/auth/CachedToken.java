package in.neuw.kbootar.auth;

import java.time.Instant;

record CachedToken(String accessToken, Instant expiresAt) {

    private static final int BUFFER_SECONDS = 30;

    boolean isExpired() {
        return Instant.now().isAfter(expiresAt.minusSeconds(BUFFER_SECONDS));
    }
}
