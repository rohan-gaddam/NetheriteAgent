package io.netherite.api;

import io.netherite.common.context.TenantContext;
import io.netherite.ingestion.model.ActionIngestionRequest;
import io.netherite.ingestion.model.ActionIngestionResponse;
import io.netherite.ingestion.service.ActionIngestionService;
import io.netherite.security.context.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for agent action ingestion.
 * Requests must be authenticated via Phase 2 filter before reaching this controller.
 */
@RestController
@RequestMapping("/v1/actions")
@RequiredArgsConstructor
public class ActionIngestionController {

    private final ActionIngestionService actionIngestionService;

    @PostMapping("/ingest")
    public ResponseEntity<ActionIngestionResponse> ingestAction(
            @Valid @RequestBody ActionIngestionRequest request) {

        TenantContext tenantContext = TenantContextHolder.getRequiredContext();

        ActionIngestionResponse response = actionIngestionService.ingest(request, tenantContext);

        return ResponseEntity.accepted().body(response);
    }
}
