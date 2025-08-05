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
        setMessages(prev => prev.map(msg => 
          msg.broadcastId === payload.broadcastId 
            ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() }
            : msg
        ));
        break;
      
      case 'MESSAGE_REMOVED':
        if (payload && payload.broadcastId) {
            setMessages(prev => prev.filter(msg => msg.broadcastId !== payload.broadcastId));
            toast({
                title: 'Message Removed',
                description: 'A broadcast message has been removed.',
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

  // REMOVED: The setInterval polling logic is now gone. The component relies solely on SSE events.

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
    // Store the original state in case we need to revert
    const originalMessages = messages;

    // Optimistically update the UI
    setMessages(prev => prev.map(msg =>
      msg.id === messageId
        ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() }
        : msg
    ));

    try {
      // Make the API call in the background
      await sseConnection.markAsRead(messageId);
    } catch (error) {
     // If the API call fails, revert the UI change and show an error
     setMessages(originalMessages);
     toast({ 
        title: 'Error', 
        description: `Failed to mark message as read for ${userId}.`, 
        variant: 'destructive' 
     });
    }
  }, [sseConnection, toast, userId, messages]);
  
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