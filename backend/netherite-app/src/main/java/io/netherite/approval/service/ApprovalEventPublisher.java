package io.netherite.approval.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class ApprovalEventPublisher {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String organizationId) {
        SseEmitter emitter = new SseEmitter(300000L);
        emitters.computeIfAbsent(organizationId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(organizationId, emitter));
        emitter.onTimeout(() -> removeEmitter(organizationId, emitter));
        emitter.onError(e -> removeEmitter(organizationId, emitter));

        log.debug("SSE subscriber connected for org={}, total={}", organizationId,
                emitters.getOrDefault(organizationId, new CopyOnWriteArrayList<>()).size());

        return emitter;
    }

    public void publishEvent(String organizationId, String eventType, Object data) {
        List<SseEmitter> deadEmitters = new ArrayList<>();
        CopyOnWriteArrayList<SseEmitter> orgEmitters = emitters.get(organizationId);
        if (orgEmitters == null || orgEmitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : orgEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType)
                        .id(UUID.randomUUID().toString())
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            orgEmitters.removeAll(deadEmitters);
            if (orgEmitters.isEmpty()) {
                emitters.remove(organizationId);
            }
            log.debug("Removed {} dead SSE emitters for org={}", deadEmitters.size(), organizationId);
        }
    }

    private void removeEmitter(String organizationId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> orgEmitters = emitters.get(organizationId);
        if (orgEmitters != null) {
            orgEmitters.remove(emitter);
            if (orgEmitters.isEmpty()) {
                emitters.remove(organizationId);
            }
        }
        log.debug("SSE emitter removed for org={}, remaining={}", organizationId,
                emitters.getOrDefault(organizationId, new CopyOnWriteArrayList<>()).size());
    }
}
