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
        description: 'Failed to fetch messages',
        variant: 'destructive',
      });
    } finally {
      setLoading(false);
    }
  }, [userId, toast]);

  // MODIFIED: This handler is now corrected to properly handle the event structure.
  const handleSseEvent = useCallback((event: { type: string; data: any }) => {
    // The payload is now in event.data
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
              // We add the payload directly to the state
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

  useEffect(() => {
    const intervalId = setInterval(() => {
        const now = new Date().getTime();
        setMessages(prevMessages => {
            const activeMessages = prevMessages.filter(message => {
                if (!message.expiresAt) return true;
                return new Date(message.expiresAt).getTime() > now;
            });

            if (activeMessages.length !== prevMessages.length) {
                return activeMessages;
            }
            return prevMessages;
        });
    }, 60000);

    return () => clearInterval(intervalId);
  }, []);

  const onConnect = useCallback(() => {
    toast({ title: 'Connected', description: 'Real-time updates enabled' });
    fetchMessages();
  }, [toast, fetchMessages]);

  const onDisconnect = useCallback(() => {
    toast({ title: 'Disconnected', description: 'Real-time updates disabled', variant: 'destructive' });
  }, [toast]);

  const onError = useCallback(() => {
    toast({ title: 'Connection Error', description: 'Failed to connect to real-time updates', variant: 'destructive' });
  }, [toast]);

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
    try {
      await sseConnection.markAsRead(messageId);
      setMessages(prev => prev.map(msg =>
        msg.id === messageId
          ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() }
          : msg
      ));
    } catch (error) {
     toast({ title: 'Error', description: 'Failed to mark message as read. Please try again.', variant: 'destructive' });
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