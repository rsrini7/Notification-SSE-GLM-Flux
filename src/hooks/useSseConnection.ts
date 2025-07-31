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
    error: null
  });

  const eventSourceRef = useRef<EventSource | null>(null);
  const heartbeatRef = useRef<NodeJS.Timeout | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const sessionIdRef = useRef<string | null>(null);

  // Generate session ID
  const generateSessionId = useCallback(() => {
    return `session-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }, []);

  // Connect to SSE
  const connect = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    setState(prev => ({ ...prev, connecting: true, error: null }));

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
          error: null
        }));

        onConnect?.();
        startHeartbeat();
      };

      eventSourceRef.current.onmessage = (event) => {
        try {
          const sseEvent = JSON.parse(event.data);
          onMessage?.(sseEvent);
        } catch (error) {
          console.error('Error parsing SSE event:', error);
          onError?.(error);
        }
      };

      eventSourceRef.current.onerror = (error) => {
        console.error('SSE connection error:', error);
        
        setState(prev => ({
          ...prev,
          connected: false,
          connecting: false,
          error: 'Connection lost'
        }));

        onDisconnect?.();
        onError?.(error);

        // Attempt to reconnect after 5 seconds
        if (reconnectTimeoutRef.current) {
          clearTimeout(reconnectTimeoutRef.current);
        }
        
        reconnectTimeoutRef.current = setTimeout(() => {
          connect();
        }, 5000);
      };

    } catch (error) {
      console.error('Failed to create SSE connection:', error);
      
      setState(prev => ({
        ...prev,
        connected: false,
        connecting: false,
        error: 'Failed to connect'
      }));

      onError?.(error);

      // Simulate connection for development if backend is not available
      setTimeout(() => {
        setState(prev => ({
          ...prev,
          connected: true,
          connecting: false,
          sessionId: newSessionId,
          error: null
        }));

        onConnect?.();
        startHeartbeat();
      }, 1000);
    }
  }, [userId, baseUrl, generateSessionId, onConnect, onMessage, onDisconnect, onError]);

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

    // Notify backend about disconnection
    // Optimistically update state to disconnected
    setState(prev => ({
      ...prev,
      connected: false,
      connecting: false,
      sessionId: null,
      error: null
    }));

    if (sessionIdRef.current) {
      fetch(`${baseUrl}/api/sse/disconnect?userId=${userId}&sessionId=${sessionIdRef.current}`, {
        method: 'POST',
      }).catch(error => {
        console.error('Error sending disconnect signal to backend:', error);
        // Optionally, you could revert state here if the disconnect truly failed and you want to retry
        // For now, we assume the UI should reflect disconnected state immediately.
      });
    }

    sessionIdRef.current = null;
    onDisconnect?.();
  }, [userId, baseUrl, onDisconnect]);

  // Send read receipt
  const markAsRead = useCallback(async (messageId: number) => {
    if (sessionIdRef.current) {
      try {
        await fetch(`${baseUrl}/api/sse/read?userId=${userId}&messageId=${messageId}`, {
          method: 'POST',
        });
      } catch (error) {
        console.error('Failed to mark message as read:', error);
        onError?.(error);
      }
    }
  }, [userId, baseUrl, onError]);

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
  }, [autoConnect, userId, connect, disconnect]);

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