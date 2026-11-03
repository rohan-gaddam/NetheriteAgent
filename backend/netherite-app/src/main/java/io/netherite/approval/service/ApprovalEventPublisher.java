package io.netherite.approval.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ApprovalEventPublisher {

    /** Absolute emitter lifetime. Unchanged: clients must treat expiry as a normal reconnect. */
    static final long EMITTER_TIMEOUT_MILLIS = 300000L;
    /** Keep-alive cadence. Well below proxy idle timeouts and the emitter lifetime. */
    static final long HEARTBEAT_INTERVAL_MILLIS = 25000L;
    /** Browser auto-retry hint sent on subscribe so native retries stay bounded. */
    static final long RECONNECT_TIME_MILLIS = 3000L;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeatScheduler;

    @PostConstruct
    void startHeartbeatScheduler() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT_INTERVAL_MILLIS, HEARTBEAT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopHeartbeatScheduler() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    public SseEmitter subscribe(String organizationId) {
        SseEmitter emitter = createEmitter();
        emitters.computeIfAbsent(organizationId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // Expected lifecycle ends (client close, absolute timeout) are normal: clean up quietly.
        emitter.onCompletion(() -> removeEmitter(organizationId, emitter));
        emitter.onTimeout(() -> {
            removeEmitter(organizationId, emitter);
            completeQuietly(emitter);
        });
        // Unexpected transport failures stay visible at debug with the cause; cleanup is the same.
        emitter.onError(e -> {
            log.debug("SSE emitter error for org={}: {}", organizationId,
                    e == null ? "unknown" : e.getMessage());
            removeEmitter(organizationId, emitter);
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .id(UUID.randomUUID().toString())
                    .reconnectTime(RECONNECT_TIME_MILLIS)
                    .data(Map.of("status", "CONNECTED"), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Client went away between subscribe and first write: expected disconnect, no error log.
            removeEmitter(organizationId, emitter);
            completeQuietly(emitter);
            log.debug("SSE initial send failed for org={} (client gone)", organizationId);
            return emitter;
        }

        log.debug("SSE subscriber connected for org={}, total={}", organizationId,
                emitters.getOrDefault(organizationId, new CopyOnWriteArrayList<>()).size());

        return emitter;
    }

    SseEmitter createEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MILLIS);
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
                // Closed/expired connection: expected disconnect, release it without error logs.
                deadEmitters.add(emitter);
            }
        }

        removeDeadEmitters(organizationId, orgEmitters, deadEmitters);
    }

    /**
     * Periodic keep-alive. Proxies close idle SSE streams; a named heartbeat keeps the
     * stream active and lets the frontend detect a stalled connection. Failures mean the
     * peer is gone, so the emitter is completed and released.
     */
    void sendHeartbeat() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
            String organizationId = entry.getKey();
            CopyOnWriteArrayList<SseEmitter> orgEmitters = entry.getValue();
            if (orgEmitters == null || orgEmitters.isEmpty()) {
                continue;
            }
            List<SseEmitter> deadEmitters = new ArrayList<>();
            for (SseEmitter emitter : orgEmitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("HEARTBEAT")
                            .id(UUID.randomUUID().toString())
                            .data(Map.of("status", "HEARTBEAT"), MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    deadEmitters.add(emitter);
                }
            }
            removeDeadEmitters(organizationId, orgEmitters, deadEmitters);
        }
    }

    private void removeDeadEmitters(String organizationId,
                                    CopyOnWriteArrayList<SseEmitter> orgEmitters,
                                    List<SseEmitter> deadEmitters) {
        if (deadEmitters.isEmpty()) {
            return;
        }
        for (SseEmitter dead : deadEmitters) {
            completeQuietly(dead);
        }
        orgEmitters.removeAll(deadEmitters);
        if (orgEmitters.isEmpty()) {
            emitters.remove(organizationId, orgEmitters);
        }
        log.debug("Removed {} dead SSE emitters for org={}", deadEmitters.size(), organizationId);
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE emitter complete failed: {}", e.getMessage());
        }
    }

    int getSubscriberCount(String organizationId) {
        CopyOnWriteArrayList<SseEmitter> orgEmitters = emitters.get(organizationId);
        return orgEmitters == null ? 0 : orgEmitters.size();
    }

    void registerForTest(String organizationId, SseEmitter emitter) {
        emitters.computeIfAbsent(organizationId, k -> new CopyOnWriteArrayList<>()).add(emitter);
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
