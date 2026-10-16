package io.netherite.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Production gateway response contract for external AI agents.
 * All responses follow this envelope. The 'status' field indicates the outcome.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayActionResponse {

    public enum Status {
        EXECUTED,
        DENIED,
        PENDING_APPROVAL,
        VALIDATION_ERROR,
        AUTHENTICATION_ERROR,
        EXECUTION_ERROR
    }

    private Status status;
    private String requestId;
    private String action;
    private String resource;

    private Integer statusCode;
    private Map<String, Object> responseBody;
    private Long executionDurationMillis;
    private String errorMessage;

    private String ticketId;
    private String ticketMessage;

    public static GatewayActionResponse executed(String requestId, String action, String resource,
                                                  int statusCode, Map<String, Object> responseBody,
                                                  long executionDurationMillis) {
        return GatewayActionResponse.builder()
                .status(Status.EXECUTED)
                .requestId(requestId)
                .action(action)
                .resource(resource)
                .statusCode(statusCode)
                .responseBody(responseBody)
                .executionDurationMillis(executionDurationMillis)
                .build();
    }

    public static GatewayActionResponse denied(String requestId, String action, String resource) {
        return GatewayActionResponse.builder()
                .status(Status.DENIED)
                .requestId(requestId)
                .action(action)
                .resource(resource)
                .build();
    }

    public static GatewayActionResponse pendingApproval(String requestId, String action, String resource,
                                                         String ticketId, String ticketMessage) {
        return GatewayActionResponse.builder()
                .status(Status.PENDING_APPROVAL)
                .requestId(requestId)
                .action(action)
                .resource(resource)
                .ticketId(ticketId)
                .ticketMessage(ticketMessage)
                .build();
    }

    public static GatewayActionResponse validationError(String message) {
        return GatewayActionResponse.builder()
                .status(Status.VALIDATION_ERROR)
                .errorMessage(message)
                .build();
    }

    public static GatewayActionResponse executionError(String requestId, String action, String resource,
                                                        String errorMessage) {
        return GatewayActionResponse.builder()
                .status(Status.EXECUTION_ERROR)
                .requestId(requestId)
                .action(action)
                .resource(resource)
                .errorMessage(errorMessage)
                .build();
    }
}
