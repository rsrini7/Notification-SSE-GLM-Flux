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

interface SseConnection {
  connected: boolean;
  connecting: boolean;
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
    connectionId: null,
    error: null,
    reconnectAttempt: 0
  });

  const MAX_RECONNECT_ATTEMPTS = 10;
  const BASE_RECONNECT_DELAY = 3000;
  const MAX_RECONNECT_DELAY = 300000;

  // --- START OF FIX ---
  // Use refs to hold the current state and callbacks to avoid stale closures
  // and make dependency arrays stable.
  const stateRef = useRef(state);
  stateRef.current = state;

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
  // --- END OF FIX ---

  const reconnectAttemptsRef = useRef(0);
  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const connectionIdRef = useRef<string | null>(null);
  const isForceDisconnectRef = useRef(false);

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

  // --- START OF FIX ---
  // The 'disconnect' function is now stable and does not depend on 'state'.
  const disconnect = useCallback((isForceDisconnect = false) => {
    console.log(`[SSE - ${userId}] Disconnect called. Is force disconnect: ${isForceDisconnect}`);
    isForceDisconnectRef.current = isForceDisconnect;
    if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    if (connectionIdRef.current && !isForceDisconnect) {
      navigator.sendBeacon(`${baseUrl}/sse/disconnect?userId=${userId}&connectionId=${connectionIdRef.current}`);
    }
    // Check the ref for the current connection state to avoid stale closures.
    if (stateRef.current.connected) {
      onDisconnectRef.current?.();
    }
    reconnectAttemptsRef.current = 0;
    connectionIdRef.current = null;
    setState({ connected: false, connecting: false, connectionId: null, error: null, reconnectAttempt: 0 });
  }, [userId, baseUrl]); // Dependency array is now stable.

  // The 'connect' function is now stable and does not depend on 'state'.
  const connect = useCallback(() => {
    // Use the ref to get the current state inside the function.
    if (eventSourceRef.current || stateRef.current.connecting) return;

    reconnectAttemptsRef.current++;
    console.log(`[SSE - ${userId}] Attempting to connect... (Attempt ${reconnectAttemptsRef.current})`);
    setState(prev => ({ ...prev, connecting: true, error: prev.reconnectAttempt > 0 ? `Reconnecting...` : null, reconnectAttempt: reconnectAttemptsRef.current }));

    const newConnectionId = generateConnectionId();
    connectionIdRef.current = newConnectionId;
    const sseUrl = `${baseUrl}/sse/connect?userId=${userId}&connectionId=${newConnectionId}`;
    
    eventSourceRef.current = new EventSource(sseUrl);

    eventSourceRef.current.onopen = () => {
      console.log(`[SSE - ${userId}] Connection successful. Connection ID: ${newConnectionId}`);
      reconnectAttemptsRef.current = 0;
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
  }, [userId, baseUrl, generateConnectionId, handleSseMessage]); // 'disconnect' is removed as it's stable.
  // --- END OF FIX ---

  const markAsRead = useCallback(async (broadcastId: number) => {
    if (!connectionIdRef.current) {
      const error = new Error("Cannot mark as read: not connected.");
      onErrorRef.current?.(error);
      throw error;
    }
    try {
      const response = await fetch(`${baseUrl}/messages/read`, {
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

  // --- START OF FIX ---
  // Main effect to manage the connection lifecycle.
  useEffect(() => {
    if (autoConnect && userId) {
      connect();
    }
    
    // The cleanup function will now call the stable version of disconnect.
    return () => {
      disconnect();
    };
  }, [autoConnect, userId, connect, disconnect]); // Dependencies 'connect' and 'disconnect' are now stable.
  // --- END OF FIX ---

  return { ...state, connect, disconnect, markAsRead };
};

export default useSseConnection;