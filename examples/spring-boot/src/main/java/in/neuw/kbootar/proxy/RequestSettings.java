package in.neuw.kbootar.proxy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    // Every field is optional on the wire. A missing one takes its default rather than the
    // Java zero value, which for sslVerification would silently disable verification.
    @JsonCreator
    static RequestSettings fromJson(
            @JsonProperty("httpVersion") String httpVersion,
            @JsonProperty("sslVerification") Boolean sslVerification,
            @JsonProperty("followRedirects") Boolean followRedirects,
            @JsonProperty("followOriginalMethod") Boolean followOriginalMethod,
            @JsonProperty("followAuthHeader") Boolean followAuthHeader,
            @JsonProperty("removeRefererOnRedirect") Boolean removeRefererOnRedirect,
            @JsonProperty("strictHttpParser") Boolean strictHttpParser,
            @JsonProperty("encodeUrl") Boolean encodeUrl,
            @JsonProperty("disableCookieJar") Boolean disableCookieJar,
            @JsonProperty("serverCipherSuite") Boolean serverCipherSuite,
            @JsonProperty("maxRedirects") Integer maxRedirects,
            @JsonProperty("disabledTlsProtocols") List<String> disabledTlsProtocols,
            @JsonProperty("cipherSuites") String cipherSuites,
            @JsonProperty("acceptEncoding") String acceptEncoding
    ) {
        RequestSettings d = defaults();
        return new RequestSettings(
                httpVersion != null ? httpVersion : d.httpVersion(),
                sslVerification != null ? sslVerification : d.sslVerification(),
                followRedirects != null ? followRedirects : d.followRedirects(),
                followOriginalMethod != null ? followOriginalMethod : d.followOriginalMethod(),
                followAuthHeader != null ? followAuthHeader : d.followAuthHeader(),
                removeRefererOnRedirect != null ? removeRefererOnRedirect : d.removeRefererOnRedirect(),
                strictHttpParser != null ? strictHttpParser : d.strictHttpParser(),
                encodeUrl != null ? encodeUrl : d.encodeUrl(),
                disableCookieJar != null ? disableCookieJar : d.disableCookieJar(),
                serverCipherSuite != null ? serverCipherSuite : d.serverCipherSuite(),
                maxRedirects != null ? maxRedirects : d.maxRedirects(),
                disabledTlsProtocols != null ? disabledTlsProtocols : d.disabledTlsProtocols(),
                cipherSuites != null ? cipherSuites : d.cipherSuites(),
                acceptEncoding != null ? acceptEncoding : d.acceptEncoding()
        );
    }

    public static RequestSettings defaults() {
        return new RequestSettings(
                "auto", true, true, false, false,
                false, false, true, false, false,
                10, List.of(), "", "gzip, br, deflate"
        );
    }
}
