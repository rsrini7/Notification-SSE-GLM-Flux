'use client';
import { useState, useCallback, useMemo, useRef } from 'react';
import { useToast } from '@/hooks/use-toast';
import { useSseConnection } from './useSseConnection';
import { userService, type UserBroadcastMessage } from '../services/api';

interface UseBroadcastMessagesOptions {
  userId: string;
  autoConnect?: boolean;
  onForcedDisconnect?: (userId: string) => void;
}

export const useBroadcastMessages = (options: UseBroadcastMessagesOptions) => {
  const {
    userId,
    autoConnect = true,
    onForcedDisconnect
  } = options;
  const [messages, setMessages] = useState<UserBroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const { toast } = useToast();

  // --- FIX #1: Use a ref to track if we are processing the initial list of messages ---
  const isInitialLoadRef = useRef(true);

  const fetchMessages = useCallback(async () => {
    setLoading(true);
    try {
      const uniqueMessages = await userService.getUserMessages(userId);
      setMessages(uniqueMessages);
    } catch (error) {
      toast({
        title: 'Error',
        description: `Failed to fetch messages for ${userId}`,
        variant: 'destructive',
      });
    } finally {
      setLoading(false);
    }
  }, [userId, toast]);

  const handleSseEvent = (event: { type: string; data: any }) => {
    const payload = event.data;
    switch (event.type) {
      case 'MESSAGE':
        if (payload) {
          if (payload.category === 'Force Logoff') {
            toast({
              title: 'Logged Off',
              description: 'Your connection has been terminated by an administrator.',
              variant: 'destructive',
            });
            sseConnection.disconnect(true);
            if (onForcedDisconnect) {
              onForcedDisconnect(userId);
            }
          }

          // --- FIX #2: Use different logic for initial load vs. real-time updates ---
          setMessages(prev => {
            const exists = prev.some(msg => msg.broadcastId === payload.broadcastId);
            if (exists) return prev;

            if (isInitialLoadRef.current) {
              // During initial load, APPEND messages to preserve the server's sort order.
              return [...prev, payload];
            } else {
              // For new real-time messages, PREPEND to show them at the top.
              toast({
                title: 'New Message',
                description: `From ${payload.senderName}: ${payload.content.substring(0, 30)}...`,
              });
              return [payload, ...prev];
            }
          });
        }
        break;

      case 'READ_RECEIPT':
        if (payload && payload.broadcastId) {
            setMessages(prev => prev.filter(msg => msg.broadcastId !== payload.broadcastId));
        }
        break;

      case 'MESSAGE_REMOVED':
        if (payload && payload.broadcastId) {
            setMessages(prev => prev.filter(msg => msg.broadcastId !== payload.broadcastId));
            toast({
                title: 'Message Removed',
                description: 'A broadcast message has been removed from your view.',
            });
        }
        break;

      case 'CONNECTED':
        // --- FIX #3: When the 'CONNECTED' event arrives, the historical stream is finished. ---
        // Switch off initial load mode so subsequent messages are treated as real-time.
        isInitialLoadRef.current = false;
        break;

      case 'HEARTBEAT':
        break;
      case 'SERVER_SHUTDOWN':
        toast({
            title: 'Server Shutdown',
            description: 'The server is restarting. You will be reconnected shortly',
        });
        sseConnection.disconnect(false);
        break;
      default:
        console.log('Unhandled SSE event type:', event.type);
    }
  };

  const onConnect = useCallback(() => {
    toast({ title: 'Connected', description: `Real-time updates enabled for ${userId}` });
    // Reset state for the new connection
    isInitialLoadRef.current = true;
    setMessages([]);
  }, [toast, userId]);
  
  const onError = useCallback(() => { /* Silent */ }, []);
  
  const onDisconnect = useCallback(() => {
    setMessages([]);
  }, []);

  const sseConnection = useSseConnection({
    userId,
    baseUrl: import.meta.env.VITE_USER_API_BASE_URL,
    autoConnect,
    onMessage: handleSseEvent,
    onConnect,
    onDisconnect,
    onError,
  });

  const markAsRead = useCallback(async (broadcastId: number) => {
    try {
      await sseConnection.markAsRead(broadcastId);
    } catch {
      toast({
        title: 'Error',
        description: 'Failed to send read receipt',
        variant: 'destructive',
      });
    }
   }, [sseConnection, toast]);

  const stats = useMemo(() => {
    const total = messages.length;
    const unread = messages.filter(msg => msg.readStatus === 'UNREAD').length;
    const read = total - unread;
    return { total, unread, read };
  }, [messages]);

  return {
    messages,
    loading,
    stats,
    sseConnection,
    actions: {
      markAsRead,
      refresh: fetchMessages,
    }
  };
};