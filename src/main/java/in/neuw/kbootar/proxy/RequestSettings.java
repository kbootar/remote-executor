package in.neuw.kbootar.proxy;

import java.util.List;

public record RequestSettings(
        String httpVersion,
        boolean sslVerification,
        boolean followRedirects,
        boolean followOriginalMethod,
        boolean followAuthHeader,
        boolean removeRefererOnRedirect,
        boolean strictHttpParser,
        boolean encodeUrl,
        boolean disableCookieJar,
        boolean serverCipherSuite,
        int maxRedirects,
        List<String> disabledTlsProtocols,
        String cipherSuites,
        String acceptEncoding
) {
    public static RequestSettings defaults() {
        return new RequestSettings(
                "auto", true, true, false, false,
                false, false, true, false, false,
                10, List.of(), "", "gzip, br, deflate"
        );
    }
}
