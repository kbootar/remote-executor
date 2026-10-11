package in.neuw.kbootar.collection;

import java.util.List;
import java.util.Map;

public record ApiRequest(
        String method,
        String url,
        Map<String, String> headers,
        String body,
        String bodyEncoding,
        List<MultipartPart> multipartBody
) {}
