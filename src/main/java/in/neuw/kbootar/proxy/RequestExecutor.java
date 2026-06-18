package in.neuw.kbootar.proxy;

import in.neuw.kbootar.auth.TokenCacheService;
import in.neuw.kbootar.collection.ApiRequest;
import in.neuw.kbootar.collection.AuthType;
import in.neuw.kbootar.collection.Environment;
import in.neuw.kbootar.collection.TlsConfig;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(RequestExecutor.class);

    private static final List<String> ALL_TLS = List.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3");

    private final TokenCacheService tokenCacheService;

    public RequestExecutor(TokenCacheService tokenCacheService) {
        this.tokenCacheService = tokenCacheService;
    }

    public Mono<ProxyResponse> execute(ApiRequest request, Environment environment, RequestSettings settings) {
        RequestSettings s = settings != null ? settings : RequestSettings.defaults();
        Instant start = Instant.now();
        String resolvedUrl = resolveVars(request.url(), environment.variables());
        log.debug("Executing {} {} with settings httpVersion={} sslVerification={} followRedirects={}",
                request.method(), resolvedUrl, s.httpVersion(), s.sslVerification(), s.followRedirects());

        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(buildHttpClient(s, environment.tlsConfig())))
                .build();

        Mono<String> tokenMono = needsOAuthToken(request)
                ? tokenCacheService.getToken(environment.id(), environment)
                : Mono.just("");

        return tokenMono.flatMap(token -> {
            WebClient.RequestBodySpec requestSpec = webClient
                    .method(HttpMethod.valueOf(request.method().toUpperCase()))
                    .uri(resolvedUrl);

            if (request.headers() != null) {
                request.headers().forEach((k, v) ->
                        requestSpec.header(k, resolveVars(v, environment.variables())));
            }

            if (!token.isBlank()) {
                requestSpec.header("Authorization", "Bearer " + token);
            }

            WebClient.RequestHeadersSpec<?> spec =
                    (request.body() != null && !request.body().isBlank())
                            ? requestSpec.bodyValue(resolveVars(request.body(), environment.variables()))
                            : requestSpec;

            return spec.exchangeToMono(response -> {
                long durationMs = Duration.between(start, Instant.now()).toMillis();
                List<String> setCookies = Objects.requireNonNullElse(
                        response.headers().asHttpHeaders().get(HttpHeaders.SET_COOKIE), List.of());
                return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new ProxyResponse(
                                response.statusCode().value(),
                                response.headers().asHttpHeaders().toSingleValueMap(),
                                setCookies,
                                body,
                                durationMs,
                                resolvedUrl));
            });
        });
    }

    private HttpClient buildHttpClient(RequestSettings s, TlsConfig tls) {
        HttpClient client = HttpClient.create();

        // SSL — trust all certs when sslVerification is off, configure protocols and ciphers
        try {
            SslContextBuilder ssl = SslContextBuilder.forClient();

            // mTLS — client certificate
            if (tls != null && tls.clientCertPath() != null && !tls.clientCertPath().isBlank()) {
                String pwd = tls.clientKeyPassword();
                if ("PKCS12".equalsIgnoreCase(tls.format())) {
                    // PKCS12: cert + key in a single keystore file
                    KeyStore ks = KeyStore.getInstance("PKCS12");
                    char[] pwdChars = (pwd != null && !pwd.isBlank()) ? pwd.toCharArray() : new char[0];
                    try (FileInputStream fis = new FileInputStream(tls.clientCertPath())) {
                        ks.load(fis, pwdChars);
                    }
                    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    kmf.init(ks, pwdChars);
                    ssl.keyManager(kmf);
                } else {
                    // PEM: separate cert and key files — Netty loads these natively
                    File certFile = new File(tls.clientCertPath());
                    File keyFile  = new File(tls.clientKeyPath());
                    ssl.keyManager(certFile, keyFile, (pwd != null && !pwd.isBlank()) ? pwd : null);
                }
                log.debug("mTLS enabled: cert={}", tls.clientCertPath());
            }

            // Custom CA — add to trust anchors in addition to system trust store
            if (tls != null && tls.caCertPath() != null && !tls.caCertPath().isBlank()) {
                ssl.trustManager(new File(tls.caCertPath()));
                log.debug("Custom CA: {}", tls.caCertPath());
            }

            if (!s.sslVerification()) {
                ssl.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            if (s.disabledTlsProtocols() != null && !s.disabledTlsProtocols().isEmpty()) {
                String[] enabled = ALL_TLS.stream()
                        .filter(p -> !s.disabledTlsProtocols().contains(p))
                        .toArray(String[]::new);
                if (enabled.length > 0) ssl.protocols(enabled);
            }
            if (s.cipherSuites() != null && !s.cipherSuites().isBlank()) {
                List<String> ciphers = Arrays.stream(s.cipherSuites().split("[,\\s]+"))
                        .map(String::trim).filter(c -> !c.isEmpty()).toList();
                if (!ciphers.isEmpty()) ssl.ciphers(ciphers);
            }
            var sslCtx = ssl.build();
            client = client.secure(spec -> spec.sslContext(sslCtx));
        } catch (Exception e) {
            log.warn("SSL context configuration failed, falling back to default: {}", e.getMessage());
        }

        // HTTP version
        client = switch (s.httpVersion() != null ? s.httpVersion() : "auto") {
            case "1.1" -> client.protocol(HttpProtocol.HTTP11);
            case "2"   -> client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11);
            default    -> client;
        };

        // Redirects — strip auth/referer headers on redirect if configured
        if (s.followRedirects()) {
            client = client.followRedirect(true, req -> {
                if (!s.followAuthHeader()) req.requestHeaders().remove(HttpHeaders.AUTHORIZATION);
                if (s.removeRefererOnRedirect()) req.requestHeaders().remove(HttpHeaders.REFERER);
            });
        } else {
            client = client.followRedirect(false);
        }

        // Strict HTTP response header parsing
        client = client.httpResponseDecoder(spec -> spec.validateHeaders(s.strictHttpParser()));

        return client;
    }

    private boolean needsOAuthToken(ApiRequest request) {
        return request.authConfig() != null
                && request.authConfig().type() == AuthType.OAUTH2_CLIENT_CREDENTIALS;
    }

    private String resolveVars(String template, Map<String, String> variables) {
        if (template == null || variables == null || variables.isEmpty()) return template;
        String result = template;
        for (var entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}
