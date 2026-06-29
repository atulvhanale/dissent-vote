package com.dissent.dto;

import java.time.OffsetDateTime;

/** Request/response payloads. Kept in one file to stay compact for the MVP. */
public class Dtos {

    public record EntityRow(long id, String type, String name, String party, String department, boolean important, long votes) {}

    public record OtpRequest(String mobile) {}

    public record VerifyRequest(String mobile, String code) {}

    public record VerifyResponse(String token, int validityMinutes) {}

    public record CastRequest(String token, long entityId, String reason) {}

    public record MySelection(long entityId, String entityName, String entityType, String reason, OffsetDateTime since) {}

    public record Issue(String reason, long votes) {}

    public record ApiError(String error) {}
}
