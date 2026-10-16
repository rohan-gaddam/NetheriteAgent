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
}

const MAX_RECONNECT_DELAY = 30000;
const INITIAL_RECONNECT_DELAY = 1000;

export function useApprovalSSE({ onEvent, enabled = true }: UseApprovalSSEOptions) {
  const [connected, setConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const attemptsRef = useRef(0);
  const onEventRef = useRef(onEvent);
  const pollFallbackRef = useRef<ReturnType<typeof setInterval> | null>(null);

  onEventRef.current = onEvent;

  const cleanup = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    if (pollFallbackRef.current) {
      clearInterval(pollFallbackRef.current);
      pollFallbackRef.current = null;
    }
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    setConnected(false);
  }, []);

  const connect = useCallback(() => {
    if (!enabled) return;
    cleanup();

    const es = new EventSource('/v1/approvals/events');
    eventSourceRef.current = es;

    es.onopen = () => {
      setConnected(true);
      attemptsRef.current = 0;
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
      es.addEventListener(eventType, ((e: MessageEvent) => {
        try {
          const data = JSON.parse(e.data);
          onEventRef.current({ type: eventType, ticketId: data.ticketId, data });
        } catch {
          // Ignore malformed events
        }
      }) as EventListener);
    }

    es.onerror = () => {
      setConnected(false);
      es.close();
      eventSourceRef.current = null;

      const delay = Math.min(
        INITIAL_RECONNECT_DELAY * Math.pow(2, attemptsRef.current),
        MAX_RECONNECT_DELAY,
      );
      attemptsRef.current++;

      reconnectTimeoutRef.current = setTimeout(() => connect(), delay);
    };
  }, [enabled, cleanup]);

  useEffect(() => {
    if (enabled) {
      connect();
    }
    return cleanup;
  }, [enabled, connect, cleanup]);

  return { connected };
}
