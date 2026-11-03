import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useApprovalSSE, getReconnectDelay, isHeartbeatStale } from './useApprovalSSE';

type Listener = (e: unknown) => void;

class MockEventSource {
  static instances: MockEventSource[] = [];
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  listeners = new Map<string, Listener[]>();
  close = vi.fn();

  constructor(public url: string) {
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, cb: Listener) {
    const list = this.listeners.get(type) ?? [];
    list.push(cb);
    this.listeners.set(type, list);
  }

  fire(type: string, data?: unknown) {
    for (const cb of this.listeners.get(type) ?? []) {
      cb(data);
    }
  }
}

describe('getReconnectDelay', () => {
  it('backs off exponentially from 1s and caps at 30s', () => {
    expect(getReconnectDelay(0)).toBe(1000);
    expect(getReconnectDelay(1)).toBe(2000);
    expect(getReconnectDelay(2)).toBe(4000);
    expect(getReconnectDelay(5)).toBe(30000);
    expect(getReconnectDelay(10)).toBe(30000);
  });
});

describe('isHeartbeatStale', () => {
  it('detects stalled connections past the timeout', () => {
    expect(isHeartbeatStale(0, 60_000, 75_000)).toBe(false);
    expect(isHeartbeatStale(0, 90_000, 75_000)).toBe(true);
  });
});

describe('useApprovalSSE', () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal('EventSource', MockEventSource as unknown as typeof EventSource);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('connects and reports connected on open, closes on unmount without leaks', () => {
    const onEvent = vi.fn();
    const { result, unmount } = renderHook(() => useApprovalSSE({ onEvent }));

    expect(MockEventSource.instances).toHaveLength(1);
    expect(result.current.connected).toBe(false);

    act(() => {
      MockEventSource.instances[0].onopen?.();
    });
    expect(result.current.connected).toBe(true);

    unmount();
    expect(MockEventSource.instances[0].close).toHaveBeenCalled();
  });

  it('reconnects with backoff after an expected timeout and resyncs state', () => {
    const onEvent = vi.fn();
    const onSync = vi.fn();
    const { result } = renderHook(() => useApprovalSSE({ onEvent, onSync }));

    act(() => {
      MockEventSource.instances[0].onopen?.();
    });
    expect(result.current.connected).toBe(true);

    // Expected server-side timeout surfaces as onerror: indicator flips, reconnect scheduled.
    act(() => {
      MockEventSource.instances[0].onerror?.();
    });
    expect(result.current.connected).toBe(false);
    expect(MockEventSource.instances).toHaveLength(1);

    // First backoff is 1s: a new EventSource replaces the dead one.
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(MockEventSource.instances).toHaveLength(2);

    // Reconnect restores current approval state (missed events are not replayed).
    act(() => {
      MockEventSource.instances[1].onopen?.();
    });
    expect(result.current.connected).toBe(true);
    expect(onSync).toHaveBeenCalledTimes(1);
  });

  it('starts polling fallback after repeated failures and stops when SSE is healthy', () => {
    const onSync = vi.fn();
    renderHook(() => useApprovalSSE({ onEvent: vi.fn(), onSync }));

    act(() => {
      MockEventSource.instances[0].onopen?.();
    });
    // Initial open is not a reconnect: no resync.
    expect(onSync).not.toHaveBeenCalled();

    // Three consecutive failures trip the polling fallback (immediate resync + interval).
    for (let i = 0; i < 3; i++) {
      const es = MockEventSource.instances[MockEventSource.instances.length - 1];
      act(() => {
        es.onerror?.();
      });
      act(() => {
        vi.advanceTimersByTime(getReconnectDelay(i));
      });
      act(() => {
        // Keep failing: do not fire onopen.
      });
    }
    expect(onSync).toHaveBeenCalled();

    const callsWhileUnhealthy = onSync.mock.calls.length;
    act(() => {
      vi.advanceTimersByTime(15000);
    });
    expect(onSync.mock.calls.length).toBeGreaterThan(callsWhileUnhealthy);

    // Healthy reconnect stops polling: advancing time triggers no further syncs
    // beyond the single reconnect resync.
    const latest = MockEventSource.instances[MockEventSource.instances.length - 1];
    act(() => {
      latest.onopen?.();
    });
    const afterHealthy = onSync.mock.calls.length;
    act(() => {
      vi.advanceTimersByTime(45000);
    });
    expect(onSync.mock.calls.length).toBe(afterHealthy);
  });

  it('delivers decision events with the backend contract and ignores heartbeat noise', () => {
    const onEvent = vi.fn();
    renderHook(() => useApprovalSSE({ onEvent }));

    act(() => {
      MockEventSource.instances[0].onopen?.();
    });

    // Backend decision payload: { ticketId, status }.
    act(() => {
      MockEventSource.instances[0].fire(
        'APPROVAL_APPROVED',
        { data: JSON.stringify({ ticketId: 't-123', status: 'APPROVED' }) },
      );
    });
    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledWith({ type: 'APPROVAL_APPROVED', ticketId: 't-123', data: { ticketId: 't-123', status: 'APPROVED' } });

    // Heartbeats reset liveness but never surface as domain events.
    act(() => {
      MockEventSource.instances[0].fire('HEARTBEAT', { data: '{}' });
    });
    expect(onEvent).toHaveBeenCalledTimes(1);

    // Malformed payloads are ignored without crashing.
    act(() => {
      MockEventSource.instances[0].fire('APPROVAL_REJECTED', { data: 'not-json' });
    });
    expect(onEvent).toHaveBeenCalledTimes(1);
  });

  it('treats heartbeats as liveness and forces reconnect when stalled', () => {
    const onEvent = vi.fn();
    renderHook(() => useApprovalSSE({ onEvent, heartbeatTimeoutMs: 9000 }));

    act(() => {
      MockEventSource.instances[0].onopen?.();
    });
    // Heartbeat arrives: watchdog must not fire.
    act(() => {
      MockEventSource.instances[0].fire('HEARTBEAT', { data: '{}' });
      vi.advanceTimersByTime(3000);
    });
    expect(MockEventSource.instances).toHaveLength(1);

    // No message for longer than the timeout: watchdog forces a reconnect.
    act(() => {
      vi.advanceTimersByTime(12000);
    });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(MockEventSource.instances.length).toBeGreaterThan(1);
  });
});
