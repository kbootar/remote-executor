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
        String cipherSuites
) {
    public static RequestSettings defaults() {
        return new RequestSettings(
                "auto", false, true, false, false,
                false, false, true, false, false,
                10, List.of(), ""
        );
    }
}
