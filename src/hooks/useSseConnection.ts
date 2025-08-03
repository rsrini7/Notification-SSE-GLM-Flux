'use client';

import { useState, useEffect, useRef, useCallback } from 'react';

interface UseSseConnectionOptions {
  userId: string;
  baseUrl?: string;
  autoConnect?: boolean;
  onMessage?: (event: any) => void;
  onConnect?: () => void;
  onDisconnect?: () => void;
  onError?: (error: any) => void;
}

interface SseConnectionState {
  connected: boolean;
  connecting: boolean;
  sessionId: string | null;
  error: string | null;
  reconnectAttempt: number;
}

// MODIFIED: List of event types the frontend will listen for.
const SSE_EVENT_TYPES = ['MESSAGE', 'READ_RECEIPT', 'MESSAGE_REMOVED', 'CONNECTED', 'HEARTBEAT'];

export const useSseConnection = (options: UseSseConnectionOptions) => {
  const {
    userId,
    baseUrl = '',
    autoConnect = true,
    onMessage,
    onConnect,
    onDisconnect,
    onError
  } = options;

  const [state, setState] = useState<SseConnectionState>({
    connected: false,
    connecting: false,
    sessionId: null,
    error: null,
    reconnectAttempt: 0
  });

  const MAX_RECONNECT_ATTEMPTS = 5;
  const BASE_RECONNECT_DELAY = 1000;
  const MAX_RECONNECT_DELAY = 30000;

  const reconnectAttemptsRef = useRef(0);
  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const disconnectRef = useRef<() => void>();

  const onConnectRef = useRef(onConnect);
  const onDisconnectRef = useRef(onDisconnect);
  const onErrorRef = useRef(onError);
  const onMessageRef = useRef(onMessage);

  useEffect(() => {
    onConnectRef.current = onConnect;
    onDisconnectRef.current = onDisconnect;
    onErrorRef.current = onError;
    onMessageRef.current = onMessage;
  }, [onConnect, onDisconnect, onError, onMessage]);

  const generateSessionId = useCallback(() => {
    return `session-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }, []);

  const disconnect = useCallback(() => {
    const wasConnected = eventSourceRef.current?.readyState === EventSource.OPEN;

    if (eventSourceRef.current) {
      // MODIFIED: Remove all specific event listeners before closing.
      SSE_EVENT_TYPES.forEach(type => {
        if (eventSourceRef.current) {
          eventSourceRef.current.removeEventListener(type, handleSseMessage as EventListener);
        }
      });
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }

    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    reconnectAttemptsRef.current = 0;

    if (sessionIdRef.current && wasConnected) {
      if (navigator.sendBeacon) {
        navigator.sendBeacon(`${baseUrl}/api/sse/disconnect?userId=${userId}&sessionId=${sessionIdRef.current}`);
      } else {
        fetch(`${baseUrl}/api/sse/disconnect?userId=${userId}&sessionId=${sessionIdRef.current}`, {
          method: 'POST',
          keepalive: true,
        }).catch(error => {
          console.error('Error sending disconnect signal to backend:', error);
        });
      }
    }

    sessionIdRef.current = null;
    onDisconnectRef.current?.();

    setState(prev => ({ ...prev, connected: false, connecting: false, sessionId: null, error: null, reconnectAttempt: 0 }));
  }, [userId, baseUrl]);
  
  // MODIFIED: A single handler for all incoming named events.
  const handleSseMessage = useCallback((event: MessageEvent) => {
    try {
      // The event `type` is now provided by the SSE 'event:' field.
      // The `data` is the JSON payload.
      const eventType = event.type.toUpperCase();
      const data = event.data ? JSON.parse(event.data) : {};
      
      // We reconstruct the object shape that the useBroadcastMessages hook expects.
      onMessageRef.current?.({ type: eventType, data: data });
    } catch (error) {
      console.error('Error parsing SSE event data:', error);
      onErrorRef.current?.(error);
    }
  }, []);


  useEffect(() => {
    disconnectRef.current = disconnect;
  }, [disconnect]);

  const connect = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }
    
    const isReconnecting = reconnectAttemptsRef.current > 0;
    setState(prev => ({ ...prev, connecting: true, error: isReconnecting ? `Reconnecting... (Attempt ${reconnectAttemptsRef.current})` : null, reconnectAttempt: reconnectAttemptsRef.current }));
    reconnectAttemptsRef.current++;

    const newSessionId = generateSessionId();
    sessionIdRef.current = newSessionId;
    const sseUrl = `${baseUrl}/api/sse/connect?userId=${userId}&sessionId=${newSessionId}`;

    try {
      eventSourceRef.current = new EventSource(sseUrl);

      eventSourceRef.current.onopen = () => {
        setState(prev => ({ ...prev, connected: true, connecting: false, sessionId: newSessionId, error: null, reconnectAttempt: 0 }));
        onConnectRef.current?.();
        reconnectAttemptsRef.current = 0;
      };

      // MODIFIED: Instead of a single .onmessage, we add listeners for each specific event type.
      SSE_EVENT_TYPES.forEach(type => {
        eventSourceRef.current?.addEventListener(type, handleSseMessage as EventListener);
      });

      eventSourceRef.current.onerror = (event: Event) => {
        console.error(`SSE connection error for user ${userId}:`, event);
        setState(prev => ({ ...prev, connected: false, connecting: false, error: 'Connection lost' }));
        onDisconnectRef.current?.();
        onErrorRef.current?.(event);
        
        eventSourceRef.current?.close();

        if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
        if (reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
          const delay = Math.min(MAX_RECONNECT_DELAY, BASE_RECONNECT_DELAY * Math.pow(2, reconnectAttemptsRef.current));
          reconnectTimeoutRef.current = setTimeout(connect, delay);
        } else {
          console.error(`SSE Connection: Max reconnection attempts reached for user ${userId}.`);
          setState(prev => ({ ...prev, error: 'Max reconnection attempts reached.' }));
        }
      };
    } catch (error) {
      console.error('Failed to create SSE connection:', error);
      setState(prev => ({ ...prev, connected: false, connecting: false, error: 'Failed to connect' }));
      onErrorRef.current?.(error);
    }
  }, [userId, baseUrl, generateSessionId, handleSseMessage]);

  const markAsRead = useCallback(async (messageId: number) => {
    if (sessionIdRef.current) {
      try {
        await fetch(`${baseUrl}/api/sse/read?userId=${userId}&messageId=${messageId}`, { method: 'POST' });
      } catch (error) {
        console.error('Failed to mark message as read:', error);
        onErrorRef.current?.(error);
        throw error;
      }
    }
  }, [userId, baseUrl]);

  useEffect(() => {
    if (autoConnect && userId) {
      connect();
    }
    return () => {
      disconnectRef.current?.();
    };
  }, [autoConnect, userId, connect]);

  return { ...state, connect, disconnect, markAsRead };
};

export default useSseConnection;