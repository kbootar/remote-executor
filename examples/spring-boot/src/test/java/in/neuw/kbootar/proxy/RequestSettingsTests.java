package in.neuw.kbootar.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSettingsTests {

    @Test
    void missingFieldsTakeTheirDefaults() {
        RequestSettings parsed = RequestSettings.fromJson(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(RequestSettings.defaults(), parsed);
        assertTrue(parsed.sslVerification(), "certificate verification must default to on");
    }

    @Test
    void explicitValuesWin() {
        RequestSettings parsed = RequestSettings.fromJson(
                "1.1", false, false, true, true, true, true, false, true, true, 3, java.util.List.of("TLSv1"), "X", "");

        assertEquals(false, parsed.sslVerification());
        assertEquals(3, parsed.maxRedirects());
        assertEquals("", parsed.acceptEncoding());
    }
}
