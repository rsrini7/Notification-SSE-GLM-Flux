'use client';

import { useState, useEffect, useCallback } from 'react';
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
    baseUrl = 'http://localhost:8081',
    autoConnect = true
  } = options;

  const [messages, setMessages] = useState<UserBroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const { toast } = useToast();

  // Fetch existing messages
  const fetchMessages = useCallback(async () => {
    setLoading(true);
    console.log('Fetching messages for userId:', userId);
    try {
      const realMessages = await userService.getUserMessages(userId);
      setMessages(realMessages);
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

  // Handle SSE events
  const handleSseEvent = useCallback((event: any) => {
    switch (event.type) {
      case 'MESSAGE':
        if (event.data) {
          setMessages(prev => {
            // Avoid duplicates
            const exists = prev.some(msg => msg.id === event.data.id);
            if (!exists) {
              return [event.data, ...prev];
            }
            return prev;
          });

          toast({
            title: 'New Message',
            description: event.data.content.substring(0, 50) + '...',
          });
        }
        break;
      
      case 'READ_RECEIPT':
        // Update message read status
        setMessages(prev => prev.map(msg => 
          msg.broadcastId === event.data.broadcastId 
            ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() }
            : msg
        ));
        break;
      
      case 'CONNECTED':
        // Connection established, fetch any pending messages (handled by onConnect callback)
        break;

      case 'HEARTBEAT':
        // Ignore heartbeat events
        break;
      
      default:
        console.log('Unhandled SSE event type:', event.type);
    }
  }, [toast]);

  // Setup SSE connection
  const onConnect = useCallback(() => {
    toast({
      title: 'Connected',
      description: 'Real-time updates enabled',
    });
    fetchMessages();
  }, [toast, fetchMessages]);

  const onDisconnect = useCallback(() => {
    toast({
      title: 'Disconnected',
      description: 'Real-time updates disabled',
      variant: 'destructive',
    });
  }, [toast]);

  const onError = useCallback(() => {
    toast({
      title: 'Connection Error',
      description: 'Failed to connect to real-time updates',
      variant: 'destructive',
    });
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

  // Mark message as read
  const markAsRead = useCallback(async (messageId: number) => {
    try {
      // 1. Make the API call to the backend first.
      await sseConnection.markAsRead(messageId);
      
      // 2. If the API call is successful, then update the local state.
      setMessages(prev => prev.map(msg =>
        msg.id === messageId
          ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() }
          : msg
      ));

      toast({
        title: 'Message Read',
        description: 'Message marked as read',
      });
    } catch (error) {
      // 3. If the API call fails, the local state is not changed, and an error is shown.
      toast({
        title: 'Error',
        description: 'Failed to mark message as read. Please try again.',
        variant: 'destructive',
      });
    }
  }, [sseConnection, toast]);

  // Mark all messages as read
  const markAllAsRead = useCallback(async () => {
    const unreadMessages = messages.filter(msg => msg.readStatus === 'UNREAD');
    
    // Use Promise.all to handle multiple requests concurrently
    try {
        await Promise.all(unreadMessages.map(message => sseConnection.markAsRead(message.id)));
        
        // If all API calls are successful, update the entire state at once
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

  // Delete message (archive)
  const deleteMessage = useCallback(async (messageId: number) => {
    // Note: This is a local-only delete for now.
    // For a real implementation, an API call to archive/delete would be needed here.
    try {
      setMessages(prev => prev.filter(msg => msg.id !== messageId));
      
      toast({
        title: 'Message Archived',
        description: 'Message has been removed from your view.',
      });
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to archive message',
        variant: 'destructive',
      });
    }
  }, [toast]);

  // Get message statistics
  const getMessageStats = useCallback(() => {
    const total = messages.length;
    const unread = messages.filter(msg => msg.readStatus === 'UNREAD').length;
    const read = messages.filter(msg => msg.readStatus === 'READ').length;
    
    return {
      total,
      unread,
      read,
      readRate: total > 0 ? (read / total) * 100 : 0
    };
  }, [messages]);

  // Filter messages by status
  const getMessagesByStatus = useCallback((status: string) => {
    return messages.filter(msg => msg.readStatus === status);
  }, [messages]);

  // Filter messages by priority
  const getMessagesByPriority = useCallback((priority: string) => {
    return messages.filter(msg => msg.priority === priority);
  }, [messages]);

  // Search messages
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
    stats: getMessageStats(),
    sseConnection,
    actions: {
      markAsRead,
      markAllAsRead,
      deleteMessage,
      refresh: fetchMessages,
      getMessagesByStatus,
      getMessagesByPriority,
      searchMessages
    }
  };
};
