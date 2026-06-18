package in.neuw.kbootar.collection;

public enum AuthType {
    NONE,
    BEARER_TOKEN,           // token embedded in headers via {{var}} resolution
    OAUTH2_CLIENT_CREDENTIALS,
    MTLS
}
