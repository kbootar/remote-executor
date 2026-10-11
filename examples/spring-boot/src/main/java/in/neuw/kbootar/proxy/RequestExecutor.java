package in.neuw.kbootar.proxy;

import in.neuw.kbootar.collection.ApiRequest;
import in.neuw.kbootar.collection.MultipartPart;
import in.neuw.kbootar.tls.DomainTlsProperties.DomainEntry;
import in.neuw.kbootar.tls.DomainTlsProperties.Kind;
import in.neuw.kbootar.tls.DomainTlsRegistry;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(RequestExecutor.class);

    private static final List<String> ALL_TLS = List.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3");

    // Maps rustls cipher suite names → JSSE names. TLS 1.3 suites differ (rustls prefixes with
    // version number: TLS13_; JSSE does not). TLS 1.2 names are identical in both.
    private static final Map<String, String> CIPHER_NAME_MAP = Map.of(
            "TLS13_AES_256_GCM_SHA384",                      "TLS_AES_256_GCM_SHA384",
            "TLS13_AES_128_GCM_SHA256",                      "TLS_AES_128_GCM_SHA256",
            "TLS13_CHACHA20_POLY1305_SHA256",                 "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",         "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",         "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",   "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",       "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",       "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"
    );

    private final DomainTlsRegistry domainTlsRegistry;

    public RequestExecutor(DomainTlsRegistry domainTlsRegistry) {
        this.domainTlsRegistry = domainTlsRegistry;
    }

    public Mono<ProxyResponse> execute(ApiRequest request, RequestSettings settings) {
        RequestSettings s = settings != null ? settings : RequestSettings.defaults();
        Instant start = Instant.now();
        URI targetUri = URI.create(request.url());

        String targetHost = extractHost(request.url());
        Optional<DomainEntry> domainTls = domainTlsRegistry.lookup(targetHost);
        domainTls.ifPresent(e -> log.debug("Domain TLS match: {} → mTLS={} customCA={}", targetHost, e.hasMtls(), e.hasCustomCa()));

        log.debug("Executing {} {} httpVersion={} sslVerification={} followRedirects={}",
                request.method(), request.url(), s.httpVersion(), s.sslVerification(), s.followRedirects());

        final WebClient webClient;
        AtomicReference<String> negotiatedHttpVersion = new AtomicReference<>("HTTP/1.1");
        try {
            HttpClient httpClient = buildHttpClient(s, domainTls.orElse(null), targetUri)
                    .doOnResponse((response, connection) ->
                            negotiatedHttpVersion.set(response.version().text()));
            webClient = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
        } catch (RuntimeException e) {
            return Mono.error(e);
        }

        HttpHeaders headers = new HttpHeaders();
        if (request.headers() != null) {
            request.headers().forEach(headers::set);
        }

        return exchange(
                webClient,
                request,
                s,
                start,
                targetUri,
                HttpMethod.valueOf(request.method().toUpperCase()),
                headers,
                true,
                0,
                negotiatedHttpVersion);
    }

    private Mono<ProxyResponse> exchange(
            WebClient webClient,
            ApiRequest request,
            RequestSettings settings,
            Instant start,
            URI uri,
            HttpMethod method,
            HttpHeaders headers,
            boolean includeBody,
            int redirectCount,
            AtomicReference<String> negotiatedHttpVersion
    ) {
        // The URL arriving from kbootar is already encoded. URI avoids WebClient treating
        // percent escapes as template input and encoding them a second time.
        WebClient.RequestBodySpec requestSpec = webClient.method(method).uri(uri);
        requestSpec.headers(outbound -> {
            outbound.addAll(headers);
            String acceptEncoding = normalizedAcceptEncoding(settings.acceptEncoding());
            if (acceptEncoding.isBlank()) {
                outbound.remove(HttpHeaders.ACCEPT_ENCODING);
            } else {
                outbound.set(HttpHeaders.ACCEPT_ENCODING, acceptEncoding);
            }
        });
        WebClient.RequestHeadersSpec<?> spec = includeBody
                ? buildBody(requestSpec, request)
                : requestSpec;

        return spec.exchangeToMono(response -> {
            if (settings.followRedirects() && isRedirect(response.statusCode())) {
                String location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                if (location == null || location.isBlank()) {
                    return toProxyResponse(response, start, uri, negotiatedHttpVersion);
                }
                if (redirectCount >= Math.max(0, settings.maxRedirects())) {
                    return response.releaseBody().then(Mono.error(new IllegalStateException(
                            "Redirect limit (" + Math.max(0, settings.maxRedirects()) + ") exceeded")));
                }

                final URI destination;
                try {
                    destination = uri.resolve(URI.create(location));
                } catch (IllegalArgumentException e) {
                    return response.releaseBody().then(Mono.error(
                            new IllegalArgumentException("Invalid redirect Location '" + location + "'", e)));
                }

                HttpMethod nextMethod = redirectedMethod(
                        response.statusCode(), method, settings.followOriginalMethod());
                boolean nextIncludesBody = includeBody && nextMethod == method;
                HttpHeaders nextHeaders = copyHeaders(headers);
                if (!nextIncludesBody) {
                    nextHeaders.remove(HttpHeaders.CONTENT_LENGTH);
                    nextHeaders.remove(HttpHeaders.CONTENT_TYPE);
                    nextHeaders.remove(HttpHeaders.TRANSFER_ENCODING);
                }
                applyRedirectHeaders(nextHeaders, uri, destination, settings);

                return response.releaseBody().then(exchange(
                        webClient,
                        request,
                        settings,
                        start,
                        destination,
                        nextMethod,
                        nextHeaders,
                        nextIncludesBody,
                        redirectCount + 1,
                        negotiatedHttpVersion));
            }

            return toProxyResponse(response, start, uri, negotiatedHttpVersion);
        });
    }

    private Mono<ProxyResponse> toProxyResponse(
            ClientResponse response,
            Instant start,
            URI resolvedUri,
            AtomicReference<String> negotiatedHttpVersion
    ) {
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
                        resolvedUri.toString(),
                        negotiatedHttpVersion.get()));
    }

    private static HttpHeaders copyHeaders(HttpHeaders source) {
        HttpHeaders copy = new HttpHeaders();
        source.forEach((name, values) -> copy.put(name, new ArrayList<>(values)));
        return copy;
    }

    private static boolean isRedirect(HttpStatusCode status) {
        return switch (status.value()) {
            case 301, 302, 303, 307, 308 -> true;
            default -> false;
        };
    }

    static HttpMethod redirectedMethod(
            HttpStatusCode status,
            HttpMethod method,
            boolean preserveOriginal
    ) {
        if (preserveOriginal) return method;
        if (status.value() == 303 && method != HttpMethod.HEAD) return HttpMethod.GET;
        if ((status.value() == 301 || status.value() == 302) && method == HttpMethod.POST) {
            return HttpMethod.GET;
        }
        return method;
    }

    static void applyRedirectHeaders(
            HttpHeaders headers,
            URI from,
            URI to,
            RequestSettings settings
    ) {
        if (!sameHost(from, to)) {
            headers.remove(HttpHeaders.COOKIE);
            headers.remove(HttpHeaders.PROXY_AUTHORIZATION);
            if (!settings.followAuthHeader()) {
                headers.remove(HttpHeaders.AUTHORIZATION);
            }
        }

        if (settings.removeRefererOnRedirect()
                || ("https".equalsIgnoreCase(from.getScheme())
                && !"https".equalsIgnoreCase(to.getScheme()))) {
            headers.remove(HttpHeaders.REFERER);
        } else {
            headers.set(HttpHeaders.REFERER, from.toString());
        }
    }

    private static boolean sameHost(URI first, URI second) {
        return first.getHost() != null
                && second.getHost() != null
                && first.getHost().equalsIgnoreCase(second.getHost());
    }

    private static String normalizedAcceptEncoding(String configured) {
        if (configured == null || configured.isBlank()) {
            return "";
        }
        return Arrays.stream(configured.split("[,\\s]+"))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(coding -> coding.equals("gzip")
                        || coding.equals("br")
                        || coding.equals("brotli")
                        || coding.equals("deflate"))
                .map(coding -> coding.equals("brotli") ? "br" : coding)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private WebClient.RequestHeadersSpec<?> buildBody(WebClient.RequestBodySpec requestSpec, ApiRequest request) {
        // Multipart/form-data
        if (request.multipartBody() != null && !request.multipartBody().isEmpty()) {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            for (MultipartPart part : request.multipartBody()) {
                if ("file".equals(part.type()) && part.base64() != null) {
                    byte[] bytes = Base64.getDecoder().decode(part.base64());
                    String mime = part.mimeType() != null && !part.mimeType().isBlank()
                            ? part.mimeType() : "application/octet-stream";
                    String filename = part.fileName() != null && !part.fileName().isBlank()
                            ? part.fileName() : part.name();
                    ByteArrayResource resource = new ByteArrayResource(bytes) {
                        @Override public String getFilename() { return filename; }
                    };
                    org.springframework.http.HttpEntity<ByteArrayResource> entity =
                            new org.springframework.http.HttpEntity<>(resource,
                                    buildPartHeaders(mime, filename));
                    parts.add(part.name(), entity);
                } else {
                    parts.add(part.name(), part.value() != null ? part.value() : "");
                }
            }
            return requestSpec.body(BodyInserters.fromMultipartData(parts));
        }

        // Binary — base64-encoded body
        if ("base64".equals(request.bodyEncoding()) && request.body() != null) {
            byte[] bytes = Base64.getDecoder().decode(request.body());
            return requestSpec.bodyValue(bytes);
        }

        // Text body (JSON, plain text, URL-encoded)
        if (request.body() != null && !request.body().isBlank()) {
            return requestSpec.bodyValue(request.body());
        }

        return requestSpec;
    }

    private HttpHeaders buildPartHeaders(String mimeType, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeType));
        headers.setContentDispositionFormData("form-data", filename);
        return headers;
    }

    HttpClient buildHttpClient(RequestSettings s, DomainEntry tls, URI targetUri) {
        HttpClient client = HttpClient.create()
                .compress(!normalizedAcceptEncoding(s.acceptEncoding()).isBlank());

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
            if (enabled.length == 0) {
                throw new IllegalArgumentException(
                        "TLS configuration disables every supported protocol");
            }
            ssl.protocols(enabled);
        }

        if (s.cipherSuites() != null && !s.cipherSuites().isBlank()) {
            List<String> ciphers = Arrays.stream(s.cipherSuites().split("[,\\s]+"))
                    .map(String::trim).filter(c -> !c.isEmpty())
                    .map(c -> CIPHER_NAME_MAP.getOrDefault(c, c))
                    .toList();
            if (!ciphers.isEmpty()) ssl.ciphers(ciphers);
        }

        try {
            var sslCtx = ssl.build();
            client = client.secure(spec -> spec.sslContext(sslCtx));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid TLS configuration: " + e.getMessage(), e);
        }

        client = switch (s.httpVersion() != null ? s.httpVersion() : "auto") {
            case "1.1" -> client.protocol(HttpProtocol.HTTP11);
            case "2"   -> client.protocol(
                    "https".equalsIgnoreCase(targetUri.getScheme()) ? HttpProtocol.H2 : HttpProtocol.H2C);
            default    -> client;
        };

        client = client.followRedirect(false);

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
