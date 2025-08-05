'use client';
import { useState, useEffect, useCallback, useMemo } from 'react';
import { useToast } from '@/hooks/use-toast';
import { useSseConnection } from './useSseConnection';
import { userService, type UserBroadcastMessage } from '../services/api';

interface UseBroadcastMessagesOptions {
  userId: string;
  baseUrl?: string;
  autoConnect?: boolean;
}

export const useBroadcastMessages = (options: UseBroadcastMessagesOptions) => {
  const {
    userId,
    baseUrl = '',
    autoConnect = true
  } = options;

  const [messages, setMessages] = useState<UserBroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const { toast } = useToast();

  const fetchMessages = useCallback(async () => {
    setLoading(true);
    try {
      const serverMessages = await userService.getUserMessages(userId);
      const now = new Date().getTime();
      const validMessages = serverMessages.filter(msg => 
        !msg.expiresAt || new Date(msg.expiresAt).getTime() > now
      );
      setMessages(validMessages);
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
        // This event can now be used for more subtle UI feedback if needed, but it won't remove the message.
        console.log(`Read receipt for broadcast ${payload.broadcastId} acknowledged.`);
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
        break;

      case 'HEARTBEAT':
        console.log(`Heartbeat received for user: ${userId}`, payload);
        break;

      default:
        console.log('Unhandled SSE event type:', event.type);
    }
  }, [toast, userId]);

  const onConnect = useCallback(() => {
    toast({ title: 'Connected', description: `Real-time updates enabled for ${userId}` });
    fetchMessages();
  }, [toast, fetchMessages, userId]);

  const onDisconnect = useCallback(() => {
    toast({ title: 'Disconnected', description: `Real-time updates disabled for ${userId}`, variant: 'destructive' });
  }, [toast, userId]);

  const onError = useCallback(() => {
    toast({ title: 'Connection Error', description: `Failed to connect for ${userId}`, variant: 'destructive' });
  }, [toast, userId]);

  const sseConnection = useSseConnection({
    userId,
    baseUrl,
    autoConnect,
    onMessage: handleSseEvent,
    onConnect,
    onDisconnect,
    onError,
  });
  
  const markAsRead = useCallback(async (messageId: number) => {
    // The function no longer performs optimistic updates.
    // It just sends the request to the server. The server's response (via SSE) will trigger the UI change.
    try {
      await sseConnection.markAsRead(messageId);
      toast({
        title: 'Action Sent',
        description: `Message marked as read for ${userId}. It will be removed shortly.`,
      });
    } catch (error) {
     toast({ 
        title: 'Error', 
        description: `Failed to mark message as read for ${userId}.`, 
        variant: 'destructive' 
     });
    }
  }, [sseConnection, toast, userId]);
  
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