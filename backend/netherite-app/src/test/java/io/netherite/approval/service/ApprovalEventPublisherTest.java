package io.netherite.approval.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Focused regression tests for the Approvals SSE timeout/reconnection fix.
 *
 * <p>Covers: expected timeout/disconnect cleanup, unexpected send failures, heartbeat
 * purging, tenant isolation, and emitter release without leaks. No Spring context;
 * plain unit tests using mocked emitters.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalEventPublisherTest {

    private ApprovalEventPublisher publisher;

    @Mock
    private SseEmitter liveEmitter;

    @Mock
    private SseEmitter deadEmitter;

    @BeforeEach
    void setUp() {
        publisher = new ApprovalEventPublisher();
    }

    @AfterEach
    void tearDown() {
        publisher.stopHeartbeatScheduler();
    }

    @Test
    @DisplayName("subscribe sends CONNECTED with retry hint and tracks the subscriber")
    void subscribeTracksSubscriber() {
        ApprovalEventPublisher subscribing = new ApprovalEventPublisher() {
            @Override
            SseEmitter createEmitter() {
                return liveEmitter;
            }
        };

        SseEmitter returned = subscribing.subscribe("org-1");

        assertEquals(liveEmitter, returned);
        assertEquals(1, subscribing.getSubscriberCount("org-1"));
        assertDoesNotThrow(() -> verify(liveEmitter).send(any(SseEmitter.SseEventBuilder.class)));
    }

    @Test
    @DisplayName("subscribe handles client gone before first write without throwing")
    void subscribeHandlesImmediateDisconnect() {
        ApprovalEventPublisher subscribing = new ApprovalEventPublisher() {
            @Override
            SseEmitter createEmitter() {
                return deadEmitter;
            }
        };
        assertDoesNotThrow(() -> {
            try {
                doThrow(new IOException("client gone")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            subscribing.subscribe("org-gone");
        });
        assertEquals(0, subscribing.getSubscriberCount("org-gone"));
    }

    @Test
    @DisplayName("expected completion callback releases the emitter")
    void expectedCompletionReleasesEmitter() {
        ApprovalEventPublisher subscribing = new ApprovalEventPublisher() {
            @Override
            SseEmitter createEmitter() {
                return liveEmitter;
            }
        };
        subscribing.subscribe("org-1");
        assertEquals(1, subscribing.getSubscriberCount("org-1"));

        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(liveEmitter).onCompletion(completion.capture());
        completion.getValue().run();

        assertEquals(0, subscribing.getSubscriberCount("org-1"));
    }

    @Test
    @DisplayName("expected timeout callback releases the emitter and completes it")
    void expectedTimeoutReleasesEmitter() {
        ApprovalEventPublisher subscribing = new ApprovalEventPublisher() {
            @Override
            SseEmitter createEmitter() {
                return liveEmitter;
            }
        };
        subscribing.subscribe("org-1");

        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(liveEmitter).onTimeout(timeout.capture());
        timeout.getValue().run();

        assertEquals(0, subscribing.getSubscriberCount("org-1"));
        verify(liveEmitter).complete();
    }

    @Test
    @DisplayName("publishEvent removes unexpected-failure emitters and completes them")
    void publishRemovesDeadEmitters() throws Exception {
        publisher.registerForTest("org-1", liveEmitter);
        publisher.registerForTest("org-1", deadEmitter);
        doThrow(new IOException("broken pipe")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        assertDoesNotThrow(() -> publisher.publishEvent("org-1", "APPROVAL_CREATED", Map.of("a", "b")));

        assertEquals(1, publisher.getSubscriberCount("org-1"));
        verify(deadEmitter).complete();
        verify(liveEmitter, never()).complete();
        verify(liveEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("heartbeat keeps live emitters and purges dead ones per organization")
    void heartbeatPurgesDeadEmitters() throws Exception {
        publisher.registerForTest("org-1", liveEmitter);
        publisher.registerForTest("org-1", deadEmitter);
        doThrow(new IllegalStateException("expired")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        publisher.sendHeartbeat();

        assertEquals(1, publisher.getSubscriberCount("org-1"));
        verify(deadEmitter).complete();
        verify(liveEmitter, never()).complete();
    }

    @Test
    @DisplayName("tenant isolation: failure in one org never affects another org")
    void tenantIsolation() throws Exception {
        publisher.registerForTest("org-a", liveEmitter);
        publisher.registerForTest("org-b", deadEmitter);
        doThrow(new IOException("gone")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        publisher.publishEvent("org-b", "APPROVAL_CREATED", Map.of("a", "b"));

        assertEquals(1, publisher.getSubscriberCount("org-a"));
        assertEquals(0, publisher.getSubscriberCount("org-b"));
        verify(liveEmitter, never()).complete();
    }
}
