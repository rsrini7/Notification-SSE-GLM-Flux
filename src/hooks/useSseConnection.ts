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
  // **NEW**: Added to provide more granular feedback to the UI
  reconnectAttempt: number;
}

export const useSseConnection = (options: UseSseConnectionOptions) => {
  const {
    userId,
    baseUrl = 'http://localhost:8081',
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
    // **NEW**: Initialize reconnect attempt count
    reconnectAttempt: 0
  });

  // **NEW**: Constants for exponential backoff strategy
  const MAX_RECONNECT_ATTEMPTS = 5;
  const BASE_RECONNECT_DELAY = 1000; // 1 second
  const MAX_RECONNECT_DELAY = 30000; // 30 seconds

  const reconnectAttemptsRef = useRef(0);
  const eventSourceRef = useRef<EventSource | null>(null);
  const heartbeatRef = useRef<NodeJS.Timeout | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const sessionIdRef = useRef<string | null>(null);

  // Refs for callbacks to prevent re-renders
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

  // Generate session ID
  const generateSessionId = useCallback(() => {
    return `session-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }, []);

  // Start heartbeat
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

          if (!response.ok) {
            throw new Error('Heartbeat failed');
          }
        } catch (error) {
          console.error('Heartbeat failed:', error);
          // Connection might be lost, will be handled by SSE error handler
        }
      }
    }, heartbeatInterval);
  }, [userId, baseUrl, heartbeatInterval]);

  // Connect to SSE
  const connect = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }
    
    // **MODIFIED**: Differentiate between initial connection and reconnection for UI feedback
    const isReconnecting = reconnectAttemptsRef.current > 0;
    console.log(isReconnecting ? `SSE Reconnection Attempt: ${reconnectAttemptsRef.current}` : 'SSE Connection: Attempting to connect...');

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
        setState(prev => ({
          ...prev,
          connected: true,
          connecting: false,
          sessionId: newSessionId,
          error: null,
          reconnectAttempt: 0 // Reset on successful connection
        }));

        onConnectRef.current?.();
        startHeartbeat();
        console.log('SSE Connection: Connected');
        reconnectAttemptsRef.current = 0; // Reset attempts on successful connection
      };
      eventSourceRef.current.onmessage = (event) => {
        try {
          if (event.data) {
            const sseEvent = JSON.parse(event.data);
            onMessageRef.current?.(sseEvent);
          }
        } catch (error) {
          console.error('Error parsing SSE event:', error);
          onErrorRef.current?.(error);
        }
      };

      eventSourceRef.current.onerror = (event: Event) => {
        console.error('SSE connection error event:', event);
        // Log specific error details if available
        if ('message' in event) {
          console.error('SSE Error Message:', (event as any).message);
        }
        if ('error' in event) {
          console.error('SSE Error Object:', (event as any).error);
        }
        
        setState(prev => ({
          ...prev,
          connected: false,
          connecting: false,
          error: 'Connection lost'
        }));
        onDisconnectRef.current?.();
        onErrorRef.current?.(event);
        console.error('SSE Connection: Error and attempting reconnect');

        if (reconnectTimeoutRef.current) {
          clearTimeout(reconnectTimeoutRef.current);
        }
        
        if (reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
          // **MODIFIED**: Implement exponential backoff with jitter
          const delay = Math.min(MAX_RECONNECT_DELAY, BASE_RECONNECT_DELAY * Math.pow(2, reconnectAttemptsRef.current));
          const jitter = delay * 0.1 * Math.random(); // Add up to 10% jitter
          const reconnectDelay = delay + jitter;

          console.log(`SSE Connection: Reconnecting in ${reconnectDelay.toFixed(2)}ms`);

          reconnectTimeoutRef.current = setTimeout(() => {
            connect();
          }, reconnectDelay);
        } else {
          console.error('SSE Connection: Max reconnection attempts reached. Stopping reconnects.');
          setState(prev => ({ ...prev, error: 'Max reconnection attempts reached. Please check your connection or try again later.' }));
        }
      };
    } catch (error) {
      console.error('Failed to create SSE connection:', error);
      setState(prev => ({
        ...prev,
        connected: false,
        connecting: false,
        error: 'Failed to connect'
      }));
      onErrorRef.current?.(error);
    }
  }, [userId, baseUrl, generateSessionId, startHeartbeat]);

  // Disconnect from SSE
  const disconnect = useCallback(() => {
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

    reconnectAttemptsRef.current = 0; // Reset reconnect attempts on manual disconnect

    // Notify backend about disconnection
    // Optimistically update state to disconnected
    setState(prev => ({
      ...prev,
      connected: false,
      connecting: false,
      sessionId: null,
      error: null,
      reconnectAttempt: 0
    }));

    if (sessionIdRef.current && state.connected) {
      fetch(`${baseUrl}/api/sse/disconnect?userId=${userId}&sessionId=${sessionIdRef.current}`, {
        method: 'POST',
      }).catch(error => {
        console.error('Error sending disconnect signal to backend:', error);
      });
    }

    sessionIdRef.current = null;
    onDisconnectRef.current?.();
  }, [userId, baseUrl, state.connected]);

  // Send read receipt
  const markAsRead = useCallback(async (messageId: number) => {
    if (sessionIdRef.current) {
      try {
        await fetch(`${baseUrl}/api/sse/read?userId=${userId}&messageId=${messageId}`, {
          method: 'POST',
        });
      } catch (error) {
        console.error('Failed to mark message as read:', error);
        onErrorRef.current?.(error);
      }
    }
  }, [userId, baseUrl]);

  // Check connection status
  const checkConnection = useCallback(async () => {
    if (sessionIdRef.current) {
      try {
        const response = await fetch(`${baseUrl}/api/sse/connected/${userId}`);
        const connected = await response.json();
        return connected;
      } catch (error) {
        console.error('Failed to check connection status:', error);
        return false;
      }
    }
    return false;
  }, [userId, baseUrl]);

  // Get connection stats
  const getConnectionStats = useCallback(async () => {
    try {
      const response = await fetch(`${baseUrl}/api/sse/stats`);
      const stats = await response.json();
      return stats;
    } catch (error) {
      console.error('Failed to get connection stats:', error);
      return null;
    }
  }, [baseUrl]);

  // Auto-connect on mount
  useEffect(() => {
    if (autoConnect && userId) {
      connect();
    }

    return () => {
      disconnect();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoConnect, userId]);

  return {
    ...state,
    connect,
    disconnect,
    markAsRead,
    checkConnection,
    getConnectionStats
  };
};

export default useSseConnection;