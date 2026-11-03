import { useEffect, useRef, useCallback, useState } from 'react';

export interface ApprovalSSEEvent {
  type: string;
  ticketId?: string;
  data: Record<string, unknown>;
}

type EventHandler = (event: ApprovalSSEEvent) => void;

interface UseApprovalSSEOptions {
  onEvent: EventHandler;
  enabled?: boolean;
  /** Called after SSE (re)connects following a disconnect, and on polling ticks while unhealthy. */
  onSync?: () => void;
  /** Stalled-connection threshold. Heartbeats arrive every 25s; default allows 3 missed. */
  heartbeatTimeoutMs?: number;
  /** Polling fallback cadence while SSE is unhealthy. */
  pollIntervalMs?: number;
  /** Consecutive failures before polling fallback starts. */
  maxFailuresBeforePoll?: number;
}

const MAX_RECONNECT_DELAY = 30000;
const INITIAL_RECONNECT_DELAY = 1000;
const DEFAULT_HEARTBEAT_TIMEOUT_MS = 75000;
const DEFAULT_POLL_INTERVAL_MS = 15000;
const DEFAULT_MAX_FAILURES_BEFORE_POLL = 3;

export function getReconnectDelay(attempts: number): number {
  return Math.min(INITIAL_RECONNECT_DELAY * Math.pow(2, Math.max(0, attempts)), MAX_RECONNECT_DELAY);
}

export function isHeartbeatStale(lastMessageAt: number, now: number, timeoutMs: number): boolean {
  return now - lastMessageAt > timeoutMs;
}

export function useApprovalSSE({
  onEvent,
  enabled = true,
  onSync,
  heartbeatTimeoutMs = DEFAULT_HEARTBEAT_TIMEOUT_MS,
  pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
  maxFailuresBeforePoll = DEFAULT_MAX_FAILURES_BEFORE_POLL,
}: UseApprovalSSEOptions) {
  const [connected, setConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollFallbackRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const watchdogRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const attemptsRef = useRef(0);
  const failuresRef = useRef(0);
  const hadDisconnectRef = useRef(false);
  const lastMessageAtRef = useRef<number>(Date.now());
  const onEventRef = useRef(onEvent);
  const onSyncRef = useRef(onSync);

  onEventRef.current = onEvent;
  onSyncRef.current = onSync;

  const stopPolling = useCallback(() => {
    if (pollFallbackRef.current) {
      clearInterval(pollFallbackRef.current);
      pollFallbackRef.current = null;
    }
  }, []);

  const startPolling = useCallback(() => {
    if (pollFallbackRef.current) return;
    if (pollIntervalMs <= 0) return;
    pollFallbackRef.current = setInterval(() => {
      onSyncRef.current?.();
    }, pollIntervalMs);
    // Immediate resync when falling back so the list never sits stale.
    onSyncRef.current?.();
  }, [pollIntervalMs]);

  const cleanup = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    stopPolling();
    if (watchdogRef.current) {
      clearInterval(watchdogRef.current);
      watchdogRef.current = null;
    }
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    setConnected(false);
  }, [stopPolling]);

  const markMessage = useCallback(() => {
    lastMessageAtRef.current = Date.now();
  }, []);

  const connect = useCallback(() => {
    if (!enabled) return;
    // Teardown the previous transport but preserve polling fallback: while SSE is
    // unhealthy, polling must keep running across reconnect attempts and only stop
    // on a successful open or final cleanup.
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    if (watchdogRef.current) {
      clearInterval(watchdogRef.current);
      watchdogRef.current = null;
    }
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }

    lastMessageAtRef.current = Date.now();
    const es = new EventSource('/v1/approvals/events');
    eventSourceRef.current = es;

    es.onopen = () => {
      setConnected(true);
      attemptsRef.current = 0;
      failuresRef.current = 0;
      markMessage();
      stopPolling();
      // Reconnect (not first connect) must restore current approval state:
      // events missed during the outage are not replayed by SSE.
      if (hadDisconnectRef.current) {
        hadDisconnectRef.current = false;
        onSyncRef.current?.();
      }
    };

    const handleDomainEvent = (eventType: string) => (e: MessageEvent) => {
      markMessage();
      try {
        const data = JSON.parse((e as MessageEvent).data);
        onEventRef.current({
          type: eventType,
          ticketId: (data as { ticketId?: string }).ticketId,
          data: data as Record<string, unknown>,
        });
      } catch {
        // Ignore malformed events
      }
    };

    const eventTypes = [
      'APPROVAL_CREATED',
      'APPROVAL_UPDATED',
      'APPROVAL_APPROVED',
      'APPROVAL_REJECTED',
      'APPROVAL_EXECUTED',
      'APPROVAL_CONTINUATION_FAILED',
    ];

    for (const eventType of eventTypes) {
      es.addEventListener(eventType, handleDomainEvent(eventType) as EventListener);
    }

    const handleLiveness = () => {
      markMessage();
    };
    es.addEventListener('HEARTBEAT', handleLiveness as EventListener);
    es.addEventListener('CONNECTED', handleLiveness as EventListener);

    es.onerror = () => {
      setConnected(false);
      hadDisconnectRef.current = true;
      failuresRef.current += 1;
      es.close();
      if (eventSourceRef.current === es) {
        eventSourceRef.current = null;
      }

      if (failuresRef.current >= maxFailuresBeforePoll) {
        startPolling();
      }

      const delay = getReconnectDelay(attemptsRef.current);
      attemptsRef.current += 1;

      reconnectTimeoutRef.current = setTimeout(() => connect(), delay);
    };

    // Stalled connections (proxy silently dropped, no onerror) must not look healthy.
    watchdogRef.current = setInterval(() => {
      if (!eventSourceRef.current) return;
      if (isHeartbeatStale(lastMessageAtRef.current, Date.now(), heartbeatTimeoutMs)) {
        const handler = es.onerror;
        if (typeof handler === 'function') {
          handler.call(es, new Event('error') as ErrorEvent);
        }
      }
    }, Math.min(heartbeatTimeoutMs / 3, 30000));
  }, [enabled, markMessage, startPolling, stopPolling, heartbeatTimeoutMs, maxFailuresBeforePoll]);

  useEffect(() => {
    if (enabled) {
      connect();
    }
    return cleanup;
  }, [enabled, connect, cleanup]);

  return { connected };
}
