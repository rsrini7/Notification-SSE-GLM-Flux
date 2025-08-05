import React from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { ScrollArea } from '../ui/scroll-area';
import { useToast } from '../../hooks/use-toast';
import { Bell, Wifi, WifiOff, XCircle } from 'lucide-react';
import { useBroadcastMessages } from '../../hooks/useBroadcastMessages';
import { type UserBroadcastMessage } from '../../services/api';

// A self-contained panel for a single user's connection and messages
const UserConnectionPanel: React.FC<{ userId: string; onRemove: (userId: string) => void }> = ({ userId, onRemove }) => {
  const { toast } = useToast();
  const {
    messages,
    loading,
    stats,
    sseConnection,
    actions,
  } = useBroadcastMessages({ userId, autoConnect: true });

  const toggleConnection = () => {
    if (sseConnection.connected) {
      sseConnection.disconnect();
    } else {
      sseConnection.connect();
    }
  };

  const markAsRead = async (messageId: number) => {
    try {
      await actions.markAsRead(messageId);
      toast({
        title: 'Message Read',
        description: `Message marked as read for ${userId}`,
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
  
  const isConnected = sseConnection.connected && !sseConnection.connecting;
  const connectionStatusText = isConnected ? 'Connected' : sseConnection.connecting ? 'Connecting...' : 'Disconnected';
  const connectionStatusColor = isConnected ? 'text-green-600' : 'text-red-600';

  return (
    <Card className="border flex flex-col">
      <CardHeader>
        <div className="flex items-center justify-between">
            <CardTitle className="flex items-center gap-2">
                <Bell className="h-5 w-5" />
                User: {userId}
            </CardTitle>
            <Button variant="ghost" size="icon" className="h-6 w-6" onClick={() => onRemove(userId)}>
                <XCircle className="h-4 w-4 text-red-500" />
            </Button>
        </div>
        <CardDescription className="flex items-center justify-between">
            <span>Real-time messages and notifications</span>
            <div className="flex items-center gap-2">
                {isConnected ? <Wifi className="h-4 w-4 text-green-600" /> : <WifiOff className="h-4 w-4 text-red-600" />}
                <span className={`text-xs ${connectionStatusColor}`}>
                    {connectionStatusText}
                </span>
            </div>
        </CardDescription>
      </CardHeader>
      <CardContent className="flex-grow flex flex-col">
        <ScrollArea className="h-72 flex-grow">
          {loading && messages.length === 0 ? (
            <div className="text-center py-8 text-gray-500">Loading messages...</div>
          ) : messages.length === 0 ? (
            <div className="text-center py-8 text-gray-500">No messages yet.</div>
          ) : (
            <div className="space-y-3 pr-4">
              {messages.map((message: UserBroadcastMessage) => (
                <div
                  key={message.id}
                  className={`border rounded-lg p-3 space-y-2 ${message.readStatus === 'UNREAD' ? 'bg-blue-50 border-blue-200' : 'bg-white'}`}
                >
                  <div className="flex items-start justify-between">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <Badge className={getPriorityColor(message.priority)}>{message.priority}</Badge>
                        <Badge variant="outline">{message.category}</Badge>
                      </div>
                      <p className="text-sm text-gray-800">{message.content}</p>
                      <p className="text-xs text-gray-500">From {message.senderName} • {new Date(message.broadcastCreatedAt).toLocaleString()}</p>
                    </div>
                    {message.readStatus === 'UNREAD' && (
                      <Button variant="outline" size="sm" className="h-7 px-2" onClick={() => markAsRead(message.id)}>
                        Mark Read
                      </Button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </ScrollArea>
        <div className="border-t pt-3 mt-4 flex justify-between items-center text-sm">
            <div>
                <span>Total: {stats.total}</span> | <span className="font-bold text-blue-600">Unread: {stats.unread}</span>
            </div>
            <Button variant="outline" size="sm" onClick={toggleConnection} disabled={sseConnection.connecting}>
                {sseConnection.connecting ? 'Connecting...' : (sseConnection.connected ? 'Disconnect' : 'Connect')}
            </Button>
        </div>
      </CardContent>
    </Card>
  );
};

export default UserConnectionPanel;