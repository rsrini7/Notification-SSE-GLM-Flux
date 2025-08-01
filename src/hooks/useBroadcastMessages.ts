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
    // START OF FIX: Default to a relative path.
    baseUrl = '',
    // END OF FIX
    autoConnect = true
  } = options;
  const [messages, setMessages] = useState<UserBroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const { toast } = useToast();
  
  const fetchMessages = useCallback(async () => {
    setLoading(true);
    try {
      const serverMessages = await userService.getUserMessages(userId);
      // Filter out already expired messages on initial fetch
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

  const handleSseEvent = useCallback((event: any) => {
    switch (event.type) {
      case 'MESSAGE':
        if (event.data) {
          setMessages(prev => {
            const exists = prev.some(msg => msg.id === event.data.id);
            if (!exists) {
              toast({
                title: 'New Message',
                description: `From ${event.data.senderName}: ${event.data.content.substring(0, 30)}...`,
              });
              return [event.data, ...prev];
            }
            return prev;
          });
        }
        break;
      
      case 'READ_RECEIPT':
        setMessages(prev => prev.map(msg => 
          msg.broadcastId === event.data.broadcastId 
            ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() }
            : msg
        ));
        break;
      
      case 'MESSAGE_REMOVED':
        if (event.data && event.data.broadcastId) {
            setMessages(prev => prev.filter(msg => msg.broadcastId !== event.data.broadcastId));
            toast({
                title: 'Message Removed',
                description: 'A broadcast message has been removed.',
            });
        }
        break;

      case 'CONNECTED':
        break;
      case 'HEARTBEAT':
        break;
      default:
        console.log('Unhandled SSE event type:', event.type);
    }
  }, [toast]);

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
      toast({ title: 'Message Read', description: 'Message marked as read' });
    } catch (error) {
      toast({ title: 'Error', description: 'Failed to mark message as read. Please try again.', variant: 'destructive' });
    }
  }, [sseConnection, toast]);

  const markAllAsRead = useCallback(async () => {
    const unreadMessages = messages.filter(msg => msg.readStatus === 'UNREAD');
    try {
        await Promise.all(unreadMessages.map(message => sseConnection.markAsRead(message.id)));
        setMessages(prev => prev.map(msg => 
            msg.readStatus === 'UNREAD' 
            ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() } 
            : msg
        ));
        toast({
            title: 'All Messages Read',
            description: 'All unread messages have been marked as read.',
        });
    } catch (error) {
        toast({
            title: 'Error',
            description: 'Could not mark all messages as read. Please try again.',
            variant: 'destructive',
        });
    }
  }, [messages, sseConnection, toast]);

  const stats = useMemo(() => {
    const total = messages.length;
    const unread = messages.filter(msg => msg.readStatus === 'UNREAD').length;
    const read = total - unread;
    return { total, unread, read, readRate: total > 0 ? (read / total) * 100 : 0 };
  }, [messages]);

  const getMessagesByStatus = useCallback((status: 'READ' | 'UNREAD') => {
    return messages.filter(msg => msg.readStatus === status);
  }, [messages]);

  const getMessagesByPriority = useCallback((priority: string) => {
    return messages.filter(msg => msg.priority === priority);
  }, [messages]);

  const searchMessages = useCallback((query: string) => {
    const lowercaseQuery = query.toLowerCase();
    return messages.filter(msg => 
      msg.content.toLowerCase().includes(lowercaseQuery) ||
      msg.senderName.toLowerCase().includes(lowercaseQuery) ||
      msg.category.toLowerCase().includes(lowercaseQuery)
    );
  }, [messages]);

  return {
    messages,
    loading,
    stats, // Return the memoized stats
    sseConnection,
    actions: {
      markAsRead,
      markAllAsRead,
      refresh: fetchMessages,
      getMessagesByStatus,
      getMessagesByPriority,
      searchMessages
    }
  };
};