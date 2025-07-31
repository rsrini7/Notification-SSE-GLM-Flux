'use client';

import { useState, useEffect, useCallback } from 'react';
import { useToast } from '@/hooks/use-toast';
import { useSseConnection } from './useSseConnection';

import { type UserBroadcastMessage } from '../services/api';

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
        // Connection established, fetch any pending messages
        fetchMessages();
        break;

      case 'HEARTBEAT':
        // Ignore heartbeat events
        break;
      
      default:
        console.log('Unhandled SSE event type:', event.type);
    }
  }, [toast]);

  // Setup SSE connection
  const sseConnection = useSseConnection({
    userId,
    baseUrl,
    autoConnect,
    onMessage: handleSseEvent,
    onConnect: () => {
      toast({
        title: 'Connected',
        description: 'Real-time updates enabled',
      });
    },
    onDisconnect: () => {
      toast({
        title: 'Disconnected',
        description: 'Real-time updates disabled',
        variant: 'destructive',
      });
    },
    onError: () => {
      toast({
        title: 'Connection Error',
        description: 'Failed to connect to real-time updates',
        variant: 'destructive',
      });
    }
  });

  // Fetch existing messages
  const fetchMessages = useCallback(async () => {
    setLoading(true);
    try {
      // In a real implementation, this would call the backend API
      // For now, we'll use mock data
      const mockMessages: UserBroadcastMessage[] = [
        {
          id: 1,
          broadcastId: 1,
          userId: userId,
          deliveryStatus: 'DELIVERED',
          readStatus: 'UNREAD',
          deliveredAt: new Date().toISOString(),
          createdAt: new Date(Date.now() - 300000).toISOString(),
          senderName: 'System Administrator',
          content: 'Welcome to the broadcast messaging system! This is a system-wide announcement.',
          priority: 'HIGH',
          category: 'SYSTEM',
          broadcastCreatedAt: new Date(Date.now() - 300000).toISOString()
        },
        {
          id: 2,
          broadcastId: 2,
          userId: userId,
          deliveryStatus: 'DELIVERED',
          readStatus: 'READ',
          deliveredAt: new Date(Date.now() - 600000).toISOString(),
          readAt: new Date(Date.now() - 300000).toISOString(),
          createdAt: new Date(Date.now() - 900000).toISOString(),
          senderName: 'Security Team',
          content: 'Important security update: Please review the new security policies.',
          priority: 'URGENT',
          category: 'SECURITY',
          broadcastCreatedAt: new Date(Date.now() - 900000).toISOString()
        },
        {
          id: 3,
          broadcastId: 3,
          userId: userId,
          deliveryStatus: 'DELIVERED',
          readStatus: 'UNREAD',
          deliveredAt: new Date(Date.now() - 120000).toISOString(),
          createdAt: new Date(Date.now() - 180000).toISOString(),
          senderName: 'HR Department',
          content: 'Monthly newsletter: Company updates and announcements.',
          priority: 'NORMAL',
          category: 'HR',
          broadcastCreatedAt: new Date(Date.now() - 180000).toISOString()
        }
      ];
      
      setMessages(mockMessages);
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

  // Mark message as read
  const markAsRead = useCallback(async (messageId: number) => {
    try {
      // Update local state
      setMessages(prev => prev.map(msg =>
        msg.id === messageId
          ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() }
          : msg
      ));

      // Notify backend via SSE
      await sseConnection.markAsRead(messageId);

      toast({
        title: 'Message Read',
        description: 'Message marked as read',
      });
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to mark message as read',
        variant: 'destructive',
      });
    }
  }, [sseConnection, toast]);

  // Mark all messages as read
  const markAllAsRead = useCallback(async () => {
    const unreadMessages = messages.filter(msg => msg.readStatus === 'UNREAD');
    
    for (const message of unreadMessages) {
      await markAsRead(message.id);
    }
  }, [messages, markAsRead]);

  // Delete message (archive)
  const deleteMessage = useCallback(async (messageId: number) => {
    try {
      // Remove from local state
      setMessages(prev => prev.filter(msg => msg.id !== messageId));
      
      toast({
        title: 'Message Archived',
        description: 'Message has been archived',
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
    const urgent = messages.filter(msg => msg.priority === 'URGENT').length;
    const high = messages.filter(msg => msg.priority === 'HIGH').length;

    return {
      total,
      unread,
      read,
      urgent,
      high,
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

  // Fetch messages on mount and when connection is established
  useEffect(() => {
    if (sseConnection.connected) {
      fetchMessages();
    }
  }, [sseConnection.connected, fetchMessages]);

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

export default useBroadcastMessages;