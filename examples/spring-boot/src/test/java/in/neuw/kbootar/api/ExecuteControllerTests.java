package in.neuw.kbootar.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void logsEveryRequestWithoutLeakingSecrets(CapturedOutput output) {
        String body = """
                {"request":{"method":"GET","url":"http://user:pw@localhost:%d/mock/ping?token=SECRET-IN-QUERY",
                 "headers":{"Authorization":"Bearer TARGET-SECRET"}},"settings":{}}
                """.formatted(port);
        client.post().uri("/api/execute").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Basic EXECUTOR-SECRET").bodyValue(body)
                .exchange().expectStatus().isOk();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.getOut())
                .contains("execute id=")
                .contains("GET http://localhost:" + port + "/mock/ping?... -> 200")
                .contains("http id=")
                .contains("POST /api/execute -> 200"));
        assertThat(output.getAll())
                .doesNotContain("SECRET-IN-QUERY")
                .doesNotContain("TARGET-SECRET")
                .doesNotContain("EXECUTOR-SECRET")
                .doesNotContain("user:pw");
    }

    @Test
    void logsRejectedRequests(CapturedOutput output) {
        client.post().uri("/api/execute").contentType(MediaType.APPLICATION_JSON).bodyValue("{not json")
                .exchange().expectStatus().isBadRequest();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.getOut())
                .contains("POST /api/execute -> 400"));
    }

    @Test
    void logsFailedExecutionsAsWarnings(CapturedOutput output) {
        String body = """
                {"request":{"method":"GET","url":"http://localhost:1/nothing-listens-here","headers":{}},"settings":{}}
                """;
        client.post().uri("/api/execute").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isEqualTo(502);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.getOut())
                .contains("WARN")
                .contains("failed: CONNECTION_REFUSED"));
    }

    @Test
    void logsTheRequestBeforeTheTargetResponds(CapturedOutput output) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer slowTarget = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slowTarget.createContext("/slow", exchange -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        slowTarget.start();
        try {
            int slowPort = slowTarget.getAddress().getPort();
            String body = """
                    {"request":{"method":"GET","url":"http://localhost:%d/slow","headers":{}},"settings":{}}
                    """.formatted(slowPort);
            CompletableFuture<Void> call = CompletableFuture.runAsync(() -> client.post().uri("/api/execute")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk());

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.getOut())
                    .contains("request GET http://localhost:" + slowPort + "/slow"));
            assertThat(output.getOut()).as("the target has not answered yet").doesNotContain("-> 204");

            release.countDown();
            call.get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.getOut())
                    .contains("response GET http://localhost:" + slowPort + "/slow -> 204"));
        } finally {
            release.countDown();
            slowTarget.stop(0);
        }
    }
}
