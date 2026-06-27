package in.neuw.kbootar.proxy;

import in.neuw.kbootar.collection.ApiRequest;
import in.neuw.kbootar.tls.DomainTlsProperties.DomainEntry;
import in.neuw.kbootar.tls.DomainTlsProperties.Kind;
import in.neuw.kbootar.tls.DomainTlsRegistry;
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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class RequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(RequestExecutor.class);

    private static final List<String> ALL_TLS = List.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3");

    private final DomainTlsRegistry domainTlsRegistry;

    public RequestExecutor(DomainTlsRegistry domainTlsRegistry) {
        this.domainTlsRegistry = domainTlsRegistry;
    }

    public Mono<ProxyResponse> execute(ApiRequest request, RequestSettings settings) {
        RequestSettings s = settings != null ? settings : RequestSettings.defaults();
        Instant start = Instant.now();

        String targetHost = extractHost(request.url());
        Optional<DomainEntry> domainTls = domainTlsRegistry.lookup(targetHost);
        domainTls.ifPresent(e -> log.debug("Domain TLS match: {} → mTLS={} customCA={}", targetHost, e.hasMtls(), e.hasCustomCa()));

        log.debug("Executing {} {} httpVersion={} sslVerification={} followRedirects={}",
                request.method(), request.url(), s.httpVersion(), s.sslVerification(), s.followRedirects());

        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(buildHttpClient(s, domainTls.orElse(null))))
                .build();

        WebClient.RequestBodySpec requestSpec = webClient
                .method(HttpMethod.valueOf(request.method().toUpperCase()))
                .uri(request.url());

        if (request.headers() != null) {
            request.headers().forEach(requestSpec::header);
        }

        WebClient.RequestHeadersSpec<?> spec =
                (request.body() != null && !request.body().isBlank())
                        ? requestSpec.bodyValue(request.body())
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
                            request.url()));
        });
    }

    private HttpClient buildHttpClient(RequestSettings s, DomainEntry tls) {
        HttpClient client = HttpClient.create();

        SslContextBuilder ssl = SslContextBuilder.forClient();

        // mTLS client cert — server-side only, from DomainTlsRegistry.
        // Wrapped individually so a bad keystore doesn't prevent sslVerification from applying.
        if (tls != null && tls.hasMtls()) {
            try {
                String pwd = tls.clientKeyPassword();
                char[] pwdChars = (pwd != null && !pwd.isBlank()) ? pwd.toCharArray() : new char[0];
                if ("PKCS12".equalsIgnoreCase(tls.formatOrDefault())) {
                    KeyStore ks = KeyStore.getInstance("PKCS12");
                    if (tls.kindOrDefault() == Kind.BASE64) {
                        byte[] bytes = Base64.getDecoder().decode(tls.clientCertBase64().strip());
                        try (var is = new ByteArrayInputStream(bytes)) { ks.load(is, pwdChars); }
                        log.debug("mTLS (PKCS12/base64): {}", tls.hostname());
                    } else {
                        try (FileInputStream fis = new FileInputStream(tls.clientCertPath())) { ks.load(fis, pwdChars); }
                        log.debug("mTLS (PKCS12/file): {}", tls.clientCertPath());
                    }
                    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    kmf.init(ks, pwdChars);
                    ssl.keyManager(kmf);
                } else {
                    String keyPwd = (pwd != null && !pwd.isBlank()) ? pwd : null;
                    if (tls.kindOrDefault() == Kind.BASE64) {
                        byte[] certBytes = Base64.getDecoder().decode(tls.clientCertBase64().strip());
                        byte[] keyBytes  = Base64.getDecoder().decode(tls.clientKeyBase64().strip());
                        ssl.keyManager(new ByteArrayInputStream(certBytes), new ByteArrayInputStream(keyBytes), keyPwd);
                        log.debug("mTLS (PEM/base64): {}", tls.hostname());
                    } else {
                        ssl.keyManager(new File(tls.clientCertPath()), new File(tls.clientKeyPath()), keyPwd);
                        log.debug("mTLS (PEM/file): {}", tls.clientCertPath());
                    }
                }
            } catch (Exception e) {
                log.warn("mTLS setup failed for {}: {}", tls.hostname(), e.getMessage());
            }
        }

        // Custom CA — server-side only, from DomainTlsRegistry
        if (tls != null && tls.hasCustomCa()) {
            try {
                if (tls.kindOrDefault() == Kind.BASE64) {
                    byte[] caBytes = Base64.getDecoder().decode(tls.caCertBase64().strip());
                    ssl.trustManager(new ByteArrayInputStream(caBytes));
                    log.debug("Custom CA (base64): {}", tls.hostname());
                } else {
                    ssl.trustManager(new File(tls.caCertPath()));
                    log.debug("Custom CA (file): {}", tls.caCertPath());
                }
            } catch (Exception e) {
                log.warn("Custom CA setup failed for {}: {}", tls.hostname(), e.getMessage());
            }
        }

        // sslVerification is always applied last so it overrides any CA config above
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

        try {
            var sslCtx = ssl.build();
            client = client.secure(spec -> spec.sslContext(sslCtx));
        } catch (Exception e) {
            log.warn("SSL context build failed: {}", e.getMessage());
        }

        client = switch (s.httpVersion() != null ? s.httpVersion() : "auto") {
            case "1.1" -> client.protocol(HttpProtocol.HTTP11);
            case "2"   -> client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11);
            default    -> client;
        };

        if (s.followRedirects()) {
            client = client.followRedirect(true, req -> {
                if (!s.followAuthHeader()) req.requestHeaders().remove(HttpHeaders.AUTHORIZATION);
                if (s.removeRefererOnRedirect()) req.requestHeaders().remove(HttpHeaders.REFERER);
            });
        } else {
            client = client.followRedirect(false);
        }

        client = client.httpResponseDecoder(spec -> spec.validateHeaders(s.strictHttpParser()));

        return client;
    }

    private String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
