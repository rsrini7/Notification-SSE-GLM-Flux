import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { ScrollArea } from '../ui/scroll-area';
import { useToast } from '../../hooks/use-toast';
import { Bell, CheckCircle, Clock, AlertCircle, Wifi, WifiOff, Settings } from 'lucide-react';
import { useBroadcastMessages } from '../../hooks/useBroadcastMessages';
import { type UserBroadcastMessage } from '../../services/api';

const BroadcastUserPanel: React.FC = () => {
  const [userId] = useState('user-001'); // In a real app, this would come from auth
  const { toast } = useToast();

  const {
    messages,
    loading,
    stats,
    sseConnection,
    actions,
  } = useBroadcastMessages({
    userId,
    autoConnect: true,
  });

  // Toggle connection
  const toggleConnection = () => {
    if (sseConnection.connected) {
      sseConnection.disconnect();
    } else {
      sseConnection.connect();
    }
  };

  // Mark message as read
  const markAsRead = async (messageId: number) => {
    try {
      await actions.markAsRead(messageId);
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


  const getPriorityColor = (priority: string) => {
    switch (priority) {
      case 'URGENT': return 'bg-red-200 text-red-900 border-red-300';
      case 'HIGH': return 'bg-orange-200 text-orange-900 border-orange-300';
      case 'NORMAL': return 'bg-blue-200 text-blue-900 border-blue-300';
      case 'LOW': return 'bg-gray-200 text-gray-900 border-gray-300';
      default: return 'bg-gray-200 text-gray-900 border-gray-300';
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

  const unreadCount = stats.unread;

  return (
    <div className="container mx-auto p-6 space-y-8">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Broadcast Messages</h1>
          <p className="text-gray-600">Real-time notifications and announcements</p>
        </div>
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            {sseConnection.connected ? (
              <><Wifi className="h-4 w-4 text-green-600" /><span className="text-green-600">Connected</span></>
            ) : sseConnection.connecting ? (
              <><Wifi className="h-4 w-4 text-yellow-600" /><span className="text-yellow-600">Connecting...</span></>
            ) : (
              <><WifiOff className="h-4 w-4 text-red-600" /><span className="text-red-600">Disconnected</span></>
            )}
          </div>
          {unreadCount > 0 && (
            <Badge variant="secondary" className="flex items-center gap-1">
              <Bell className="h-3 w-3" />
              {unreadCount} unread
            </Badge>
          )}
          <Button
            variant="outline"
            size="sm"
            onClick={toggleConnection}
            disabled={sseConnection.connecting}
          >
            {sseConnection.connected ? 'Disconnect' : 'Connect'}
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
        <div className="lg:col-span-3">
          <Card className="border">
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
                {loading && messages.length === 0 ? (
                  <div className="text-center py-8">
                    <p className="text-gray-500">Loading messages...</p>
                  </div>
                ) : messages.length === 0 ? (
                  <div className="text-center py-8">
                    <Bell className="h-12 w-12 text-gray-400 mx-auto mb-4" />
                    <p className="text-gray-500">No messages yet</p>
                  </div>
                ) : (
                  <div className="space-y-4">
                    {messages.map((message: UserBroadcastMessage) => (
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
                              onClick={() => markAsRead(message.id)}
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
          <Card className="border">
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
                <div className="text-gray-600 font-mono text-xs">{sseConnection.sessionId || 'Not connected'}</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Status</div>
                <div className="text-gray-600 capitalize">
                  {sseConnection.connected ? 'Connected' : sseConnection.connecting ? 'Connecting' : 'Disconnected'}
                </div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Connection Type</div>
                <div className="text-gray-600">Server-Sent Events</div>
              </div>
            </CardContent>
          </Card>

          <Card className="border">
            <CardHeader>
              <CardTitle>Message Statistics</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-sm">
                <div className="font-medium">Total Messages</div>
                <div className="text-gray-600">{stats.total}</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Unread Messages</div>
                <div className="text-gray-600">{stats.unread}</div>
              </div>
              <div className="text-sm">
                <div className="font-medium">Read Messages</div>
                <div className="text-gray-600">{stats.read}</div>
              </div>
            </CardContent>
          </Card>

          <Card className="border">
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