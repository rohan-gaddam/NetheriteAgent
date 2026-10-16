package io.netherite.api;

import io.netherite.approval.service.ApprovalEventPublisher;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1/approvals")
@RequiredArgsConstructor
public class ApprovalSseController {

    private final ApprovalEventPublisher eventPublisher;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents() {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        return eventPublisher.subscribe(orgId);
    }
}
