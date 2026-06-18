package in.neuw.kbootar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        // 32 zero-bytes as base64 — test-only, never use in production
        "kbootar.encryption.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class KbootarApplicationTests {

    @Test
    void contextLoads() {
    }
}
