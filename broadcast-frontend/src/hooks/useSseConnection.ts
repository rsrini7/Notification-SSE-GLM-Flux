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

// CHANGED: Renamed from SseConnectionState
interface SseConnection {
  connected: boolean;
  connecting: boolean;
  // CHANGED: Renamed from sessionId
  connectionId: string | null;
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

  const [state, setState] = useState<SseConnection>({
    connected: false,
    connecting: false,
    // CHANGED: Renamed from sessionId
    connectionId: null,
    error: null,
    reconnectAttempt: 0
  });

  const MAX_RECONNECT_ATTEMPTS = 10;
  const BASE_RECONNECT_DELAY = 3000;
  const MAX_RECONNECT_DELAY = 300000;

  const reconnectAttemptsRef = useRef(0);
  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  // CHANGED: Renamed from sessionIdRef
  const connectionIdRef = useRef<string | null>(null);

  const onConnectRef = useRef(onConnect);
  const onDisconnectRef = useRef(onDisconnect);
  const onErrorRef = useRef(onError);
  const onMessageRef = useRef(onMessage);
  const isForceDisconnectRef = useRef(false);

  useEffect(() => {
    onConnectRef.current = onConnect;
    onDisconnectRef.current = onDisconnect;
    onErrorRef.current = onError;
    onMessageRef.current = onMessage;
  }, [onConnect, onDisconnect, onError, onMessage]);

  // CHANGED: Renamed from generateSessionId
  const generateConnectionId = useCallback(() => {
    return `conn-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }, []);

  const handleSseMessage = useCallback((event: MessageEvent) => {
    try {
      const eventType = event.type.toUpperCase();
      const data = event.data ? JSON.parse(event.data) : {};
      console.log(`[SSE - ${userId}] Received event:`, { type: eventType, data });
      onMessageRef.current?.({ type: eventType, data: data });
    } catch (error) {
      console.error(`[SSE - ${userId}] Error parsing event data:`, error, { eventData: event.data });
      onErrorRef.current?.(error);
    }
  }, [userId]);

  const connect = useCallback(() => {
    if (eventSourceRef.current || state.connecting) return;

    reconnectAttemptsRef.current++;
    console.log(`[SSE - ${userId}] Attempting to connect... (Attempt ${reconnectAttemptsRef.current})`);
    setState(prev => ({ ...prev, connecting: true, error: prev.reconnectAttempt > 0 ? `Reconnecting...` : null, reconnectAttempt: reconnectAttemptsRef.current }));

    const newConnectionId = generateConnectionId();
    connectionIdRef.current = newConnectionId;
    // CHANGED: URL parameter is now connectionId
    const sseUrl = `${baseUrl}/api/sse/connect?userId=${userId}&connectionId=${newConnectionId}`;
    
    eventSourceRef.current = new EventSource(sseUrl);

    eventSourceRef.current.onopen = () => {
      console.log(`[SSE - ${userId}] Connection successful. Connection ID: ${newConnectionId}`);
      reconnectAttemptsRef.current = 0;
      // CHANGED: State field renamed
      setState(prev => ({ ...prev, connected: true, connecting: false, connectionId: newConnectionId, error: null, reconnectAttempt: 0 }));
      onConnectRef.current?.();
    };
    
    SSE_EVENT_TYPES.forEach(type => {
      eventSourceRef.current?.addEventListener(type, handleSseMessage as EventListener);
    });

    eventSourceRef.current.onerror = () => {
      console.warn(`[SSE - ${userId}] Connection lost.`);
      eventSourceRef.current?.close();
      eventSourceRef.current = null;
      setState(prev => ({...prev, connected: false, connecting: false}));
      onErrorRef.current?.(new Error('SSE connection error'));
      
      if (isForceDisconnectRef.current) {
        console.log(`[SSE - ${userId}] Force disconnect detected. Auto-reconnect disabled.`);
        setState(prev => ({ ...prev, error: 'Connection terminated by server.' }));
        return;
      }

      if (reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
        const backoff = Math.min(MAX_RECONNECT_DELAY, BASE_RECONNECT_DELAY * 2 ** reconnectAttemptsRef.current);
        const jitter = Math.random() * 1000;
        const delay = backoff + jitter;
        console.log(`[SSE - ${userId}] Scheduling reconnect in ${delay.toFixed(0)}ms.`);
        reconnectTimeoutRef.current = setTimeout(connect, delay);
      } else {
        console.error(`[SSE - ${userId}] Max reconnection attempts reached. Giving up.`);
        setState(prev => ({ ...prev, error: 'Max reconnection attempts reached.' }));
      }
    };
  }, [userId, baseUrl, state.connecting, generateConnectionId, handleSseMessage]);

  const disconnect = useCallback((isForceDisconnect = false) => {
    console.log(`[SSE - ${userId}] Disconnect called. Is force disconnect: ${isForceDisconnect}`);
    isForceDisconnectRef.current = isForceDisconnect;
    if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    // CHANGED: Use connectionId in disconnect beacon
    if (connectionIdRef.current && !isForceDisconnect) {
      navigator.sendBeacon(`${baseUrl}/api/sse/disconnect?userId=${userId}&connectionId=${connectionIdRef.current}`);
    }
    if (state.connected) {
      onDisconnectRef.current?.();
    }
    reconnectAttemptsRef.current = 0;
    connectionIdRef.current = null;
    // CHANGED: State field renamed
    setState({ connected: false, connecting: false, connectionId: null, error: null, reconnectAttempt: 0 });
  }, [userId, baseUrl, state.connected]);

  const markAsRead = useCallback(async (broadcastId: number) => {
    if (!connectionIdRef.current) {
      const error = new Error("Cannot mark as read: not connected.");
      onErrorRef.current?.(error);
      throw error;
    }
    try {
      const response = await fetch(`${baseUrl}/api/user/messages/read`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ userId, broadcastId }),
      });

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
    if (autoConnect && userId) {
      connect();
    }
    
    return () => {
      disconnect();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoConnect, userId]);

  return { ...state, connect, disconnect, markAsRead };
};

export default useSseConnection;