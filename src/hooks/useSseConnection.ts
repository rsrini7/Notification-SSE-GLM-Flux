'use client';

import { useState, useEffect, useRef, useCallback } from 'react';

interface UseSseConnectionOptions {
  userId: string;
  baseUrl?: string;
  autoConnect?: boolean;
  onMessage?: (event: { type: string, data: any }) => void;
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

  // MODIFIED: Increased max attempts for more resilience
  const MAX_RECONNECT_ATTEMPTS = 10;
  const BASE_RECONNECT_DELAY = 3000;
  const MAX_RECONNECT_DELAY = 300000;

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

  const handleSseMessage = useCallback((event: MessageEvent) => {
    try {
      const eventType = event.type.toUpperCase();
      const data = event.data ? JSON.parse(event.data) : {};
      onMessageRef.current?.({ type: eventType, data: data });
    } catch (error) {
      console.error('Error parsing SSE event data:', error, { eventData: event.data });
      onErrorRef.current?.(error);
    }
  }, []);

  const disconnect = useCallback(() => {
    const wasConnected = eventSourceRef.current?.readyState === EventSource.OPEN;
    if (eventSourceRef.current) {
      SSE_EVENT_TYPES.forEach(type => {
        eventSourceRef.current?.removeEventListener(type, handleSseMessage as EventListener);
      });
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
    reconnectAttemptsRef.current = 0;
    if (sessionIdRef.current && wasConnected) {
      fetch(`${baseUrl}/api/sse/disconnect?userId=${userId}&sessionId=${sessionIdRef.current}`, {
        method: 'POST',
        keepalive: true,
      }).catch(error => console.warn('Ignoring potential error from disconnect fetch:', error));
    }
    sessionIdRef.current = null;
    if (state.connected) onDisconnectRef.current?.();
    setState(prev => ({ ...prev, connected: false, connecting: false, sessionId: null, error: null, reconnectAttempt: 0 }));
  }, [userId, baseUrl, state.connected, handleSseMessage]);

  useEffect(() => {
    disconnectRef.current = disconnect;
  }, [disconnect]);
  
  // MODIFIED: Extracted scheduleReconnect into its own function for clarity and reuse.
  const scheduleReconnect = useCallback(() => {
    if (reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
      const delay = Math.min(MAX_RECONNECT_DELAY, BASE_RECONNECT_DELAY * 2 ** reconnectAttemptsRef.current);
      console.log(`SSE: Scheduling reconnect for user ${userId} in ${delay}ms`);
      if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = setTimeout(() => connect(true), delay);
    } else {
      console.error(`SSE: Max reconnection attempts reached for user ${userId}.`);
      setState(prev => ({ ...prev, error: 'Max reconnection attempts reached.', connecting: false }));
    }
  }, [userId]);

  // MODIFIED: The connect function now takes an isRetry flag.
  const connect = useCallback((isRetry = false) => {
    if (eventSourceRef.current) eventSourceRef.current.close();
    
    if (isRetry) {
      reconnectAttemptsRef.current++;
    } else {
      reconnectAttemptsRef.current = 1; // Start with attempt 1
    }
    
    setState(prev => ({ ...prev, connecting: true, error: isRetry ? `Reconnecting... (Attempt ${reconnectAttemptsRef.current})` : null, reconnectAttempt: reconnectAttemptsRef.current }));

    const newSessionId = generateSessionId();
    sessionIdRef.current = newSessionId;
    const sseUrl = `${baseUrl}/api/sse/connect?userId=${userId}&sessionId=${newSessionId}`;

    try {
      eventSourceRef.current = new EventSource(sseUrl);

      eventSourceRef.current.onopen = () => {
        setState(prev => ({ ...prev, connected: true, connecting: false, sessionId: newSessionId, error: null, reconnectAttempt: 0 }));
        onConnectRef.current?.();
        reconnectAttemptsRef.current = 0; // Reset on successful connection
      };

      SSE_EVENT_TYPES.forEach(type => {
        eventSourceRef.current?.addEventListener(type, handleSseMessage as EventListener);
      });

      eventSourceRef.current.onerror = () => {
        eventSourceRef.current?.close();
        onErrorRef.current?.(new Error('SSE connection error'));
        // MODIFIED: Centralized reconnect scheduling
        scheduleReconnect();
      };
    } catch (error) {
      console.error('Failed to create SSE connection:', error);
      onErrorRef.current?.(error);
      // MODIFIED: Centralized reconnect scheduling for initial synchronous failures
      scheduleReconnect();
    }
  }, [userId, baseUrl, generateSessionId, handleSseMessage, scheduleReconnect]);

  const markAsRead = useCallback(async (messageId: number) => {
    if (!sessionIdRef.current) {
      const error = new Error("Cannot mark as read: not connected.");
      onErrorRef.current?.(error);
      throw error;
    }
    try {
      const response = await fetch(`${baseUrl}/api/sse/read?userId=${userId}&messageId=${messageId}`, { method: 'POST' });
      if (!response.ok) {
        throw new Error(`Failed to mark message as read. Status: ${response.status}`);
      }
    } catch (error) {
      console.error('Failed to mark message as read:', error);
      onErrorRef.current?.(error);
      throw error;
    }
  }, [userId, baseUrl]);

  useEffect(() => {
    if (autoConnect && userId) connect();
    return () => {
      disconnectRef.current?.();
    };
  }, [autoConnect, userId, connect]);

  return { ...state, connect: () => connect(false), disconnect, markAsRead };
};

export default useSseConnection;