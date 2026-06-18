package in.neuw.kbootar.collection;

public record AuthConfig(AuthType type) {

    public static AuthConfig none() {
        return new AuthConfig(AuthType.NONE);
    }

    public static AuthConfig oauth2ClientCredentials() {
        return new AuthConfig(AuthType.OAUTH2_CLIENT_CREDENTIALS);
    }
}
