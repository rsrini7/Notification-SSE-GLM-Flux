'use client';
import { useState, useCallback, useMemo, useEffect } from 'react';
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
  const [isDegraded, setIsDegraded] = useState(false);
  const { toast } = useToast();

  const fetchMessages = useCallback(async () => {
    setLoading(true);
    try {
      const initialMessages = await userService.getUserMessages(userId);
      setMessages(initialMessages);
    } catch (error) {
      toast({
        title: 'Error',
        description: `Failed to fetch messages for ${userId}`,
        variant: 'destructive',
      });
      setMessages([]);
    } finally {
      setLoading(false);
    }
  }, [userId, toast]);

  useEffect(() => {
    fetchMessages();
  }, [fetchMessages]);

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
            return;
          }
          
          setMessages(prev => {
            const exists = prev.some(msg => msg.broadcastId === payload.broadcastId);
            if (exists) return prev;

            toast({
              title: 'New Message',
              description: `From ${payload.senderName}: ${payload.content.substring(0, 30)}...`,
            });
            return [payload, ...prev];
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
        // This event is now just for logging or UI status.
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
      case 'CONNECTION_LIMIT_REACHED': // NEW CASE
        toast({
          title: 'Connection Limit Reached',
          description: `Real-time updates are disabled for this panel for ${userId}.`,
          variant: 'destructive',
        });
        setIsDegraded(true); // Set degraded mode
        sseConnection.disconnect(true); // Ensure connection is fully terminated
        break;
      default:
        console.log('Unhandled SSE event type:', event.type);
    }
  };

  const onConnect = useCallback(() => {
    toast({ title: 'Connected', description: `Real-time updates enabled for ${userId}` });
    fetchMessages();
  }, [toast, userId, fetchMessages]);
  
  const onDisconnect = useCallback(() => {
    setMessages([]);
  }, []);
  
  const onError = useCallback(() => { /* Silent */ }, []);

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
    isDegraded,
    actions: { 
      markAsRead, 
      refresh: fetchMessages
    }
  };
};