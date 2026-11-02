package in.neuw.kbootar.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecuteControllerTests {

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private WebTestClient.ResponseSpec execute(String settingsJson) {
        String body = """
                {"request":{"method":"GET","url":"http://localhost:%d/mock/ping","headers":{}},"settings":%s}
                """.formatted(port, settingsJson);
        return client.post().uri("/api/execute").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    @Test
    void acceptsSettingsWithMissingAndUnknownFields() {
        // The desktop app omits serverCipherSuite and adds sendUserAgent.
        execute("""
                {"httpVersion":"auto","sslVerification":true,"followRedirects":true,"followOriginalMethod":false,
                 "followAuthHeader":false,"removeRefererOnRedirect":false,"strictHttpParser":false,"encodeUrl":true,
                 "disableCookieJar":false,"maxRedirects":10,"disabledTlsProtocols":[],"cipherSuites":"",
                 "sendUserAgent":true,"acceptEncoding":"gzip, br, deflate"}
                """)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo(200)
                .jsonPath("$.resolvedUrl").isEqualTo("http://localhost:" + port + "/mock/ping");
    }

    @Test
    void acceptsEmptySettings() {
        execute("{}").expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo(200);
    }
}
