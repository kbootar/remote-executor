package in.neuw.kbootar.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("scope") String scope
) {}
