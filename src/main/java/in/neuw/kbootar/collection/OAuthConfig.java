package in.neuw.kbootar.collection;

public record OAuthConfig(
        String tokenUri,
        String encryptedClientId,
        String encryptedClientSecret,
        String scope
) {}
