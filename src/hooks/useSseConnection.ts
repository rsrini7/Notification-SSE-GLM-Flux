'use client';

import { useState, useEffect, useRef, useCallback } from 'react';

interface UseSseConnectionOptions {
  userId: string;
  baseUrl?: string;
  autoConnect?: boolean;
  heartbeatInterval?: number;
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

export const useSseConnection = (options: UseSseConnectionOptions) => {
  const {
    userId,
    baseUrl = '',
    autoConnect = true,
    heartbeatInterval = 30000,
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

  const MAX_RECONNECT_ATTEMPTS = 2;
  const BASE_RECONNECT_DELAY = 1000;
  const MAX_RECONNECT_DELAY = 30000;

  const reconnectAttemptsRef = useRef(0);
  const eventSourceRef = useRef<EventSource | null>(null);
  const heartbeatRef = useRef<NodeJS.Timeout | null>(null);
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

  const startHeartbeat = useCallback(() => {
    if (heartbeatRef.current) {
      clearInterval(heartbeatRef.current);
    }

    heartbeatRef.current = setInterval(async () => {
      if (sessionIdRef.current) {
        try {
          const response = await fetch(`${baseUrl}/api/sse/heartbeat?userId=${userId}&sessionId=${sessionIdRef.current}`, {
            method: 'POST',
          });
          if (!response.ok) throw new Error('Heartbeat failed');
        } catch (error) {
          console.error('Heartbeat failed:', error);
        }
      }
    }, heartbeatInterval);
  }, [userId, baseUrl, heartbeatInterval]);

  const disconnect = useCallback(() => {
    const wasConnected = eventSourceRef.current?.readyState === EventSource.OPEN;

    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    if (heartbeatRef.current) {
      clearInterval(heartbeatRef.current);
      heartbeatRef.current = null;
    }
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    reconnectAttemptsRef.current = 0;

    if (sessionIdRef.current && wasConnected) {
      fetch(`${baseUrl}/api/sse/disconnect?userId=${userId}&sessionId=${sessionIdRef.current}`, {
        method: 'POST',
      }).catch(error => {
        console.error('Error sending disconnect signal to backend:', error);
      });
    }

    sessionIdRef.current = null;
    onDisconnectRef.current?.();

    setState(prev => ({
      ...prev,
      connected: false,
      connecting: false,
      sessionId: null,
      error: null,
      reconnectAttempt: 0
    }));
  }, [userId, baseUrl]);

  useEffect(() => {
    disconnectRef.current = disconnect;
  }, [disconnect]);

  const connect = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }
    
    const isReconnecting = reconnectAttemptsRef.current > 0;
    console.log(isReconnecting ? `SSE Reconnection Attempt: ${reconnectAttemptsRef.current}` : `SSE Connection: Attempting to connect for user ${userId}...`);

    setState(prev => ({ 
        ...prev, 
        connecting: true, 
        error: isReconnecting ? `Connection lost. Reconnecting... (Attempt ${reconnectAttemptsRef.current})` : null,
        reconnectAttempt: reconnectAttemptsRef.current
    }));
    reconnectAttemptsRef.current++;

    const newSessionId = generateSessionId();
    sessionIdRef.current = newSessionId;
    const sseUrl = `${baseUrl}/api/sse/connect?userId=${userId}&sessionId=${newSessionId}`;

    try {
      eventSourceRef.current = new EventSource(sseUrl);

      eventSourceRef.current.onopen = () => {
        setState(prev => ({ ...prev, connected: true, connecting: false, sessionId: newSessionId, error: null, reconnectAttempt: 0 }));
        onConnectRef.current?.();
        startHeartbeat();
        console.log(`SSE Connection: Connected for user ${userId}`);
        reconnectAttemptsRef.current = 0;
      };

      eventSourceRef.current.onmessage = (event) => {
        try {
          if (event.data) onMessageRef.current?.(JSON.parse(event.data));
        } catch (error) {
          console.error('Error parsing SSE event:', error);
          onErrorRef.current?.(error);
        }
      };

      eventSourceRef.current.onerror = (event: Event) => {
        console.error(`SSE connection error for user ${userId}:`, event);
        
        setState(prev => ({ ...prev, connected: false, connecting: false, error: 'Connection lost' }));
        onDisconnectRef.current?.();
        onErrorRef.current?.(event);

        if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
        
        if (reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
          const delay = Math.min(MAX_RECONNECT_DELAY, BASE_RECONNECT_DELAY * Math.pow(2, reconnectAttemptsRef.current));
          const jitter = delay * 0.1 * Math.random();
          const reconnectDelay = delay + jitter;
          console.log(`SSE Connection: Reconnecting in ${reconnectDelay.toFixed(2)}ms for user ${userId}`);
          reconnectTimeoutRef.current = setTimeout(connect, reconnectDelay);
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
  }, [userId, baseUrl, generateSessionId, startHeartbeat]);

  const markAsRead = useCallback(async (messageId: number) => {
    if (sessionIdRef.current) {
      try {
        await fetch(`${baseUrl}/api/sse/read?userId=${userId}&messageId=${messageId}`, { method: 'POST' });
      } catch (error) {
        console.error('Failed to mark message as read:', error);
        onErrorRef.current?.(error);
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

  return {
    ...state,
    connect,
    disconnect,
    markAsRead,
  };
};

export default useSseConnection;