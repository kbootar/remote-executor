package in.neuw.kbootar.proxy;

import in.neuw.kbootar.collection.ApiRequest;
import in.neuw.kbootar.tls.DomainTlsProperties;
import in.neuw.kbootar.tls.DomainTlsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestExecutorTests {

    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void defaultSettingsVerifyTlsCertificates() {
        assertTrue(RequestSettings.defaults().sslVerification());
    }

    @Test
    void preserveOriginalMethodControlsPostRedirects() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes
                        .post("/start", (request, response) ->
                                request.receive().then(response
                                        .status(302)
                                        .header(HttpHeaders.LOCATION, "/final")
                                        .send()))
                        .route(request -> request.uri().equals("/final"), (request, response) ->
                                request.receive()
                                        .aggregate()
                                        .asString()
                                        .defaultIfEmpty("")
                                        .flatMap(body -> response.sendString(Mono.just(
                                                request.method().name() + "|" + body)).then())))
                .bindNow();

        RequestExecutor executor = executor();
        String startUrl = "http://127.0.0.1:" + server.port() + "/start";
        ApiRequest request = new ApiRequest(
                "POST", startUrl, Map.of(), "payload", null, List.of());

        ProxyResponse preserved = executor.execute(request, settings(true, false, 10))
                .block(Duration.ofSeconds(5));
        ProxyResponse converted = executor.execute(request, settings(false, false, 10))
                .block(Duration.ofSeconds(5));

        assertEquals("POST|payload", preserved.body());
        assertEquals("GET|", converted.body());
        assertTrue(preserved.resolvedUrl().endsWith("/final"));
    }

    @Test
    void redirectLimitIsEnforced() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get("/loop", (request, response) -> response
                        .status(302)
                        .header(HttpHeaders.LOCATION, "/loop")
                        .send()))
                .bindNow();

        ApiRequest request = new ApiRequest(
                "GET",
                "http://127.0.0.1:" + server.port() + "/loop",
                Map.of(),
                null,
                null,
                List.of());

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                executor().execute(request, settings(false, false, 2))
                        .block(Duration.ofSeconds(5)));

        assertTrue(error.getMessage().contains("Redirect limit (2) exceeded"));
    }

    @Test
    void crossHostAuthorizationFollowsItsSetting() {
        URI from = URI.create("https://one.example/start");
        URI to = URI.create("https://two.example/end");

        HttpHeaders stripped = redirectHeaders();
        RequestExecutor.applyRedirectHeaders(stripped, from, to, settings(false, false, 10));
        assertFalse(stripped.containsKey(HttpHeaders.AUTHORIZATION));
        assertFalse(stripped.containsKey(HttpHeaders.COOKIE));

        HttpHeaders sameHost = redirectHeaders();
        RequestExecutor.applyRedirectHeaders(
                sameHost,
                from,
                URI.create("https://one.example/end"),
                settings(false, false, 10));
        assertEquals("Bearer secret", sameHost.getFirst(HttpHeaders.AUTHORIZATION));

        HttpHeaders forwarded = redirectHeaders();
        RequestExecutor.applyRedirectHeaders(forwarded, from, to, settings(false, true, 10));
        assertEquals("Bearer secret", forwarded.getFirst(HttpHeaders.AUTHORIZATION));
        assertFalse(forwarded.containsKey(HttpHeaders.COOKIE));
    }

    @Test
    void disablingEveryTlsProtocolFails() {
        RequestSettings settings = new RequestSettings(
                "auto",
                true,
                true,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                10,
                List.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"),
                "",
                "gzip, br, deflate");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                executor().buildHttpClient(settings, null, URI.create("https://example.com")));

        assertTrue(error.getMessage().contains("disables every supported protocol"));
    }

    @Test
    void acceptEncodingIsForwardedToTheTarget() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get("/encoding", (request, response) ->
                        response.sendString(Mono.just(
                                request.requestHeaders().get(HttpHeaders.ACCEPT_ENCODING)))))
                .bindNow();

        ApiRequest request = new ApiRequest(
                "GET",
                "http://127.0.0.1:" + server.port() + "/encoding",
                Map.of(),
                null,
                null,
                List.of());

        ProxyResponse result = executor().execute(request, settings(false, false, 10))
                .block(Duration.ofSeconds(5));

        assertEquals("gzip, br, deflate", result.body());
        assertEquals("HTTP/1.1", result.httpVersion());
    }

    private RequestExecutor executor() {
        return new RequestExecutor(new DomainTlsRegistry(new DomainTlsProperties(List.of())));
    }

    private static RequestSettings settings(
            boolean preserveMethod,
            boolean followAuth,
            int maxRedirects
    ) {
        return new RequestSettings(
                "auto",
                false,
                true,
                preserveMethod,
                followAuth,
                false,
                false,
                true,
                false,
                false,
                maxRedirects,
                List.of(),
                "",
                "gzip, br, deflate");
    }

    private static HttpHeaders redirectHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("secret");
        headers.set(HttpHeaders.COOKIE, "session=private");
        return headers;
    }
}
