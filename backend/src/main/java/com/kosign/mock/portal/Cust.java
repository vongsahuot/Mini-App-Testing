package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Json;

/** The customer Mobile Backend resolved from the session (C3); Mini App Service never takes it from the app. */
public record Cust(String id, String name, String phone, String segment) {
    public static Cust from(JsonNode body) {
        JsonNode c = body.path("customer");
        String id = Json.str(c, "id");
        if (id == null) throw ApiException.badRequest("INVALID_REQUEST", "customer is required");
        return new Cust(id, Json.str(c, "name"), Json.str(c, "phone"), Json.str(c, "segment"));
    }
}
