'use client';
import { useState, useCallback, useMemo } from 'react';
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

  const fetchMessages = useCallback(async () => {
    setLoading(true);
    try {
      const [targetedMessages, groupMessages] = await Promise.all([
        userService.getUserMessages(userId),
        userService.getGroupMessages(userId),
      ]);
      
      const allMessages = [...targetedMessages, ...groupMessages];
      const uniqueMessages = Array.from(new Map(allMessages.map(msg => [msg.id, msg])).values());
      uniqueMessages.sort((a, b) => new Date(b.broadcastCreatedAt).getTime() - new Date(a.broadcastCreatedAt).getTime());

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

  const handleSseEvent = useCallback((event: { type: string; data: any }) => {
    const payload = event.data;
    switch (event.type) {
      case 'MESSAGE':
        if (payload) {
          // Check for Force Logoff category
          if (payload.category === 'Force Logoff') {
            toast({
              title: 'Logged Off',
              description: 'Your connection has been terminated by an administrator.',
              variant: 'destructive',
            });
            // Call disconnect with the new force flag
            sseConnection.disconnect(true); 

            // NEW: If the callback exists, call it to trigger the panel's removal
            if (onForcedDisconnect) {
              onForcedDisconnect(userId);
            }
          }

          setMessages(prev => {
            const exists = prev.some(msg => msg.id === payload.id);
            if (!exists) {
              toast({
                title: 'New Message',
                description: `From ${payload.senderName}: ${payload.content.substring(0, 30)}...`,
              });
              return [payload, ...prev];
            }
            return prev;
          });
        }
        break;
      
      case 'READ_RECEIPT':
        console.log(`Read receipt for broadcast ${payload.broadcastId} acknowledged.`);
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
        console.log(`SSE Connection Confirmed via Event for user: ${userId}`, payload);
        fetchMessages();
        break;
      case 'HEARTBEAT':
        break;
      default:
        console.log('Unhandled SSE event type:', event.type);
    }
  }, [toast, userId, onForcedDisconnect, fetchMessages]);

  const onConnect = useCallback(() => {
    toast({ title: 'Connected', description: `Real-time updates enabled for ${userId}` });
  }, [toast, fetchMessages, userId]);

  const onDisconnect = useCallback(() => { /* Silent */ }, []);
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
      // The action now sends the request. The UI update is handled by the SSE event.
      await sseConnection.markAsRead(broadcastId); // CHANGED: from messageId to broadcastId
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