package in.neuw.kbootar.api;

import in.neuw.kbootar.collection.ApiRequest;
import in.neuw.kbootar.proxy.RequestSettings;

public record ExecuteRequest(
        ApiRequest request,
        RequestSettings settings
) {}
