import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { ScrollArea } from '../ui/scroll-area';
import { useToast } from '../../hooks/use-toast';
import { Bell, CheckCircle, Clock, AlertCircle, Wifi, WifiOff, Settings } from 'lucide-react';
import { 
  userService, 
  type UserBroadcastMessage, 
  type PollResponse 
} from '../../services/api';

const BroadcastUserPanel: React.FC = () => {
  const [messages, setMessages] = useState<UserBroadcastMessage[]>([]);
  const [connectionStatus, setConnectionStatus] = useState<'connected' | 'disconnected' | 'connecting'>('disconnected');
  const [userId] = useState('user-001'); // In a real app, this would come from auth
  const [sessionId, setSessionId] = useState<string>('');
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const refreshIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastTimestampRef = useRef<string>('0');
  const { toast } = useToast();

  // Fetch existing messages
  const fetchMessages = useCallback(async () => {
    try {
      const data = await userService.getUserMessages(userId);
      // Sort messages by timestamp (newest first)
      const sortedMessages = data.sort((a: UserBroadcastMessage, b: UserBroadcastMessage) => 
        new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
      );
      setMessages(sortedMessages);
      
      // Update the last timestamp to the newest message timestamp
      if (data.length > 0) {
        const newestMessage = data.reduce((newest: UserBroadcastMessage, current: UserBroadcastMessage) => 
          new Date(current.createdAt).getTime() > new Date(newest.createdAt).getTime() ? current : newest
        );
        lastTimestampRef.current = new Date(newestMessage.createdAt).getTime().toString();
      }
    } catch (error) {
      console.error('Error fetching messages:', error);
      setMessages([]); // Start with empty messages instead of mock data
    }
  }, [userId]);

  // Poll for new messages
  const pollForMessages = useCallback(async () => {
    try {
      const data: PollResponse = await userService.pollForMessages(userId, lastTimestampRef.current);
      
      if (data.messages.length > 0) {
        // Process new messages
        data.messages.forEach((msg) => {
          // The new SSE system delivers messages directly, not wrapped in type/data
          setMessages(prev => {
            // Check if message already exists using broadcast ID as the primary key
            const exists = prev.some((existing) => 
              existing.broadcastId === msg.broadcastId || existing.id === msg.id
            );
            
            if (!exists) {
              // Show toast for new message, but avoid spamming
              const now = Date.now();
              const lastToastTime = localStorage.getItem('lastToastTime');
              const lastToastBroadcastId = localStorage.getItem('lastToastBroadcastId');
              
              // Only show toast if it's not the same broadcast as the last toast within 30 seconds
              if (!lastToastBroadcastId || lastToastBroadcastId !== msg.broadcastId || !lastToastTime || now - parseInt(lastToastTime) > 30000) {
                toast({
                  title: 'New Message',
                  description: msg.content.substring(0, 50) + '...',
                });
                localStorage.setItem('lastToastBroadcastId', msg.broadcastId);
                localStorage.setItem('lastToastTime', now.toString());
              }
              
              // Transform the message to match the expected format
              const transformedMessage: UserBroadcastMessage = {
                id: msg.id,
                broadcastId: msg.broadcastId,
                userId: userId,
                deliveryStatus: 'DELIVERED',
                readStatus: 'UNREAD',
                deliveredAt: new Date(msg.timestamp).toISOString(),
                createdAt: new Date(msg.timestamp).toISOString(),
                senderName: msg.senderName,
                content: msg.content,
                priority: msg.priority,
                category: msg.category,
                broadcastCreatedAt: msg.createdAt
              };
              
              // Add new message and sort all messages by timestamp (newest first)
              const updatedMessages = [...prev, transformedMessage];
              return updatedMessages.sort((a, b) => 
                new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
              );
            }
            return prev;
          });
        });
        
        // Update last timestamp
        lastTimestampRef.current = data.timestamp;
      }
    } catch (error) {
      console.error('Error polling for messages:', error);
    }
  }, [userId, toast]);

  // Stop polling
  const stopPolling = useCallback(() => {
    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
      pollIntervalRef.current = null;
    }
    if (refreshIntervalRef.current) {
      clearInterval(refreshIntervalRef.current);
      refreshIntervalRef.current = null;
    }

    setConnectionStatus('disconnected');
    
    // Notify backend about disconnection
    if (sessionId) {
      userService.disconnectSSE(userId, sessionId).catch(console.error);
    }
  }, [sessionId, userId]);

  // Start polling
  const startPolling = useCallback(() => {
    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
    }
    if (refreshIntervalRef.current) {
      clearInterval(refreshIntervalRef.current);
    }

    setConnectionStatus('connecting');
    
    // Generate session ID
    const newSessionId = `session-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    setSessionId(newSessionId);

    // Don't reset timestamp - keep it from the initial fetch
    // lastTimestampRef.current = '0'; // Removed this line
    
    // Initial poll
    pollForMessages().then(() => {
      setConnectionStatus('connected');
      toast({
        title: 'Connected',
        description: 'Real-time updates enabled',
      });
    }).catch(() => {
      setConnectionStatus('disconnected');
      toast({
        title: 'Connection Failed',
        description: 'Could not establish connection',
        variant: 'destructive',
      });
    });

    // Start regular polling every 3 seconds
    pollIntervalRef.current = setInterval(pollForMessages, 3000);
    
    // Start refreshing messages every 30 seconds to clean up expired ones
    refreshIntervalRef.current = setInterval(fetchMessages, 30000);
  }, [pollForMessages, fetchMessages, toast]);

  // Toggle connection
  const toggleConnection = () => {
    if (connectionStatus === 'connected') {
      stopPolling();
    } else {
      startPolling();
    }
  };

  // Mark message as read
  const markAsRead = async (messageId: string, _broadcastId: string) => {
    try {
      // Update local state
      setMessages(prev => prev.map(msg => 
        msg.id === messageId 
          ? { ...msg, readStatus: 'READ', readAt: new Date().toISOString() }
          : msg
      ));

      // Notify backend
      await userService.markMessageAsRead(userId, messageId);

      toast({
        title: 'Message Read',
        description: 'Message marked as read',
      });
    } catch {
      toast({
        title: 'Error',
        description: 'Failed to mark message as read',
        variant: 'destructive',
      });
    }
  };

  useEffect(() => {
    fetchMessages();
    
    return () => {
      stopPolling();
    };
  }, [userId, fetchMessages, stopPolling]);

  const getPriorityColor = (priority: string) => {
    switch (priority) {
      case 'URGENT': return 'bg-red-100 text-red-800 border-red-200';
      case 'HIGH': return 'bg-orange-100 text-orange-800 border-orange-200';
      case 'NORMAL': return 'bg-blue-100 text-blue-800 border-blue-200';
      case 'LOW': return 'bg-gray-100 text-gray-800 border-gray-200';
      default: return 'bg-gray-100 text-gray-800 border-gray-200';
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'READ':
        return <CheckCircle className="h-4 w-4 text-green-600" />;
      case 'DELIVERED':
        return <Clock className="h-4 w-4 text-blue-600" />;
      default:
        return <AlertCircle className="h-4 w-4 text-gray-600" />;
    }
  };

  const unreadCount = messages.filter(msg => msg.readStatus === 'UNREAD').length;

  return (
    <div className="container mx-auto p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Broadcast Messages</h1>
          <p className="text-gray-600">Real-time notifications and announcements</p>
        </div>
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            {connectionStatus === 'connected' ? (
              <><Wifi className="h-4 w-4 text-green-600" /><span className="text-green-600">Connected</span></>
            ) : connectionStatus === 'connecting' ? (
              <><Wifi className="h-4 w-4 text-yellow-600" /><span className="text-yellow-600">Connecting...</span></>
            ) : (
              <><WifiOff className="h-4 w-4 text-red-600" /><span className="text-red-600">Disconnected</span></>
            )}
          </div>
          {unreadCount > 0 && (
            <Badge variant="destructive" className="flex items-center gap-1">
              <Bell className="h-3 w-3" />
              {unreadCount} unread
            </Badge>
          )}
          <Button
            variant="outline"
            size="sm"
            onClick={toggleConnection}
            disabled={connectionStatus === 'connecting'}
          >
            {connectionStatus === 'connected' ? 'Disconnect' : 'Connect'}
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
        <div className="lg:col-span-3">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Bell className="h-5 w-5" />
                Messages
              </CardTitle>
              <CardDescription>
                Your broadcast messages and notifications
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ScrollArea className="h-96">
                {messages.length === 0 ? (
                  <div className="text-center py-8">
                    <Bell className="h-12 w-12 text-gray-400 mx-auto mb-4" />
                    <p className="text-gray-500">No messages yet</p>
                  </div>
                ) : (
                  <div className="space-y-4">
                    {messages.map((message) => (
                      <div
                        key={message.id}
                        className={`border rounded-lg p-4 space-y-3 ${
                          message.readStatus === 'UNREAD' ? 'bg-blue-50 border-blue-200' : 'bg-white'
                        }`}
                      >
                        <div className="flex items-start justify-between">
                          <div className="space-y-1">
                            <div className="flex items-center gap-2">
                              <Badge className={getPriorityColor(message.priority)}>
                                {message.priority}
                              </Badge>
                              <Badge variant="outline">{message.category}</Badge>
                              <div className="flex items-center gap-1">
                                {getStatusIcon(message.readStatus)}
                                <span className="text-xs text-gray-500">
                                  {message.readStatus === 'READ' ? 'Read' : 
                                   message.readStatus === 'UNREAD' ? 'Unread' : 'Pending'}
                                </span>
                              </div>
                            </div>
                            <p className="text-sm text-gray-800">{message.content}</p>
                            <p className="text-xs text-gray-500">
                              From {message.senderName} • {new Date(message.broadcastCreatedAt).toLocaleString()}
                            </p>
                          </div>
                          {message.readStatus === 'UNREAD' && (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => markAsRead(message.id, message.broadcastId)}
                            >
                              Mark as Read
                            </Button>
                          )}
                        </div>
                        {message.readAt && (
                          <div className="text-xs text-gray-500">
                            Read at {new Date(message.readAt).toLocaleString()}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </ScrollArea>
            </CardContent>
          </Card>
        </div>

        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Settings className="h-5 w-5" />
                Connection Info
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-sm">
                <div className="font-medium">User ID</div>
                <div className="text-gray-600">{userId}</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Session ID</div>
                <div className="text-gray-600 font-mono text-xs">{sessionId || 'Not connected'}</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Status</div>
                <div className="text-gray-600 capitalize">{connectionStatus}</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Connection Type</div>
                <div className="text-gray-600">HTTP Polling</div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Message Statistics</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-sm">
                <div className="font-medium">Total Messages</div>
                <div className="text-gray-600">{messages.length}</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Unread Messages</div>
                <div className="text-gray-600">{unreadCount}</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Read Messages</div>
                <div className="text-gray-600">{messages.filter(msg => msg.readStatus === 'READ').length}</div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>System Status</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-sm">
                <div className="font-medium">Backend API</div>
                <div className="text-gray-600">Java Spring Boot</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Database</div>
                <div className="text-gray-600">h2</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Message Queue</div>
                <div className="text-gray-600">Kafka</div>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
};

export default BroadcastUserPanel;