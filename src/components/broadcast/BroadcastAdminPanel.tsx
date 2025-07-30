'use client';

import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { useToast } from '@/hooks/use-toast';
import { Send, Users, Clock, CheckCircle, AlertCircle, XCircle } from 'lucide-react';

interface BroadcastMessage {
  id: string;
  senderId: string;
  senderName: string;
  content: string;
  targetType: string;
  targetIds: string[];
  priority: string;
  category: string;
  createdAt: string;
  status: string;
  totalTargeted: number;
  totalDelivered: number;
  totalRead: number;
  startTime: string;
  endTime?: string;
  isImmediate: boolean;
}

interface BroadcastStats {
  totalTargeted: number;
  totalDelivered: number;
  totalRead: number;
  deliveryRate: number;
  readRate: number;
}

const BroadcastAdminPanel: React.FC = () => {
  const [broadcasts, setBroadcasts] = useState<BroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBroadcast, setSelectedBroadcast] = useState<BroadcastMessage | null>(null);
  const [stats, setStats] = useState<BroadcastStats | null>(null);
  const [deliveryDetails, setDeliveryDetails] = useState<any[]>([]);
  const [activeTab, setActiveTab] = useState('create');
  const [manageFilter, setManageFilter] = useState('all'); // 'all', 'active', 'scheduled'
  const { toast } = useToast();

  // Form state
  const [formData, setFormData] = useState({
    senderId: 'admin-001',
    senderName: 'System Administrator',
    content: '',
    targetType: 'ALL',
    targetIds: '',
    priority: 'NORMAL',
    category: 'GENERAL',
    isImmediate: true,
    startTime: '',
    endTime: ''
  });

  // Fetch broadcasts
  const fetchBroadcasts = async (filter = 'all') => {
    try {
      const response = await fetch(`/api/broadcasts?filter=${filter}`);
      if (response.ok) {
        const data = await response.json();
        setBroadcasts(data);
      }
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to fetch broadcasts',
        variant: 'destructive',
      });
    }
  };

  // Fetch broadcast stats
  const fetchBroadcastStats = async (broadcastId: string) => {
    try {
      const response = await fetch(`/api/broadcasts/${broadcastId}/stats`);
      if (response.ok) {
        const data = await response.json();
        setStats(data);
      } else {
        toast({
          title: 'Error',
          description: `Failed to fetch broadcast statistics: ${response.status} ${response.statusText}`,
          variant: 'destructive',
        });
      }
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to fetch broadcast statistics',
        variant: 'destructive',
      });
    }
  };

  // Fetch delivery details
  const fetchDeliveryDetails = async (broadcastId: string) => {
    try {
      const response = await fetch(`/api/broadcasts/${broadcastId}/deliveries`);
      if (response.ok) {
        const data = await response.json();
        return data;
      }
      return [];
    } catch (error) {
      console.error('Error fetching delivery details:', error);
      return [];
    }
  };

  // Create broadcast
  const createBroadcast = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      const payload = {
        ...formData,
        targetIds: formData.targetType === 'ALL' ? [] : formData.targetIds.split(',').map(id => id.trim()),
        startTime: formData.isImmediate ? new Date().toISOString() : new Date(formData.startTime).toISOString(),
        endTime: formData.endTime ? new Date(formData.endTime).toISOString() : null
      };

      const response = await fetch('/api/broadcasts', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      });

      if (response.ok) {
        const newBroadcast = await response.json();
        setBroadcasts(prev => [newBroadcast, ...prev]);
        
        // Refresh the broadcast list to ensure we have the latest data
        await fetchBroadcasts(manageFilter);
        
        // Reset form
        setFormData({
          senderId: 'admin-001',
          senderName: 'System Administrator',
          content: '',
          targetType: 'ALL',
          targetIds: '',
          priority: 'NORMAL',
          category: 'GENERAL',
          isImmediate: true,
          startTime: '',
          endTime: ''
        });

        toast({
          title: 'Success',
          description: 'Broadcast created successfully',
        });
      } else {
        throw new Error('Failed to create broadcast');
      }
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to create broadcast',
        variant: 'destructive',
      });
    } finally {
      setLoading(false);
    }
  };

  // Cancel broadcast
  const cancelBroadcast = async (broadcastId: string) => {
    try {
      const response = await fetch(`/api/broadcasts/${broadcastId}`, {
        method: 'DELETE',
      });

      if (response.ok) {
        setBroadcasts(prev => prev.map(b => 
          b.id === broadcastId ? { ...b, status: 'CANCELLED' } : b
        ));
        
        // Refresh the broadcast list to ensure we have the latest data
        await fetchBroadcasts(manageFilter);
        
        toast({
          title: 'Success',
          description: 'Broadcast cancelled successfully',
        });
      }
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to cancel broadcast',
        variant: 'destructive',
      });
    }
  };

  useEffect(() => {
    fetchBroadcasts(manageFilter);
  }, [manageFilter]);

  useEffect(() => {
    if (selectedBroadcast) {
      fetchBroadcastStats(selectedBroadcast.id);
      fetchDeliveryDetails(selectedBroadcast.id).then(setDeliveryDetails);
    }
  }, [selectedBroadcast]);

  const getPriorityColor = (priority: string) => {
    switch (priority) {
      case 'URGENT': return 'bg-red-100 text-red-800';
      case 'HIGH': return 'bg-orange-100 text-orange-800';
      case 'NORMAL': return 'bg-blue-100 text-blue-800';
      case 'LOW': return 'bg-gray-100 text-gray-800';
      default: return 'bg-gray-100 text-gray-800';
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'ACTIVE': return 'bg-green-100 text-green-800';
      case 'EXPIRED': return 'bg-yellow-100 text-yellow-800';
      case 'CANCELLED': return 'bg-red-100 text-red-800';
      case 'SCHEDULED': return 'bg-purple-100 text-purple-800';
      default: return 'bg-gray-100 text-gray-800';
    }
  };

  const getBroadcastStatus = (broadcast: BroadcastMessage) => {
    const now = new Date();
    const startTime = new Date(broadcast.startTime);
    const endTime = broadcast.endTime ? new Date(broadcast.endTime) : null;
    
    if (broadcast.status === 'CANCELLED') return 'CANCELLED';
    if (endTime && now > endTime) return 'EXPIRED';
    if (now >= startTime) return 'ACTIVE';
    return 'SCHEDULED';
  };

  return (
    <div className="container mx-auto p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Broadcast Admin Panel</h1>
          <p className="text-gray-600">Manage and send broadcast messages to users</p>
        </div>
        <Badge variant="outline" className="text-sm">
          {broadcasts.length} Total Broadcasts
        </Badge>
      </div>

      <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-6">
        <TabsList className="grid w-full grid-cols-3">
          <TabsTrigger value="create">Create Broadcast</TabsTrigger>
          <TabsTrigger value="manage">Manage Broadcasts</TabsTrigger>
          <TabsTrigger value="stats">Statistics</TabsTrigger>
        </TabsList>

        <TabsContent value="create">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Send className="h-5 w-5" />
                Create New Broadcast
              </CardTitle>
              <CardDescription>
                Send a message to all users or selected groups
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={createBroadcast} className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <Label htmlFor="senderId">Sender ID</Label>
                    <Input
                      id="senderId"
                      value={formData.senderId}
                      onChange={(e) => setFormData(prev => ({ ...prev, senderId: e.target.value }))}
                      required
                    />
                  </div>
                  <div>
                    <Label htmlFor="senderName">Sender Name</Label>
                    <Input
                      id="senderName"
                      value={formData.senderName}
                      onChange={(e) => setFormData(prev => ({ ...prev, senderName: e.target.value }))}
                      required
                    />
                  </div>
                </div>

                <div>
                  <Label htmlFor="content">Message Content</Label>
                  <Textarea
                    id="content"
                    placeholder="Enter your broadcast message..."
                    value={formData.content}
                    onChange={(e) => setFormData(prev => ({ ...prev, content: e.target.value }))}
                    rows={4}
                    required
                  />
                </div>

                <div className="grid grid-cols-3 gap-4">
                  <div>
                    <Label htmlFor="targetType">Target Type</Label>
                    <Select value={formData.targetType} onValueChange={(value) => setFormData(prev => ({ ...prev, targetType: value }))}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="ALL">All Users</SelectItem>
                        <SelectItem value="SELECTED">Selected Users</SelectItem>
                        <SelectItem value="ROLE">By Role</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div>
                    <Label htmlFor="priority">Priority</Label>
                    <Select value={formData.priority} onValueChange={(value) => setFormData(prev => ({ ...prev, priority: value }))}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="LOW">Low</SelectItem>
                        <SelectItem value="NORMAL">Normal</SelectItem>
                        <SelectItem value="HIGH">High</SelectItem>
                        <SelectItem value="URGENT">Urgent</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div>
                    <Label htmlFor="category">Category</Label>
                    <Input
                      id="category"
                      value={formData.category}
                      onChange={(e) => setFormData(prev => ({ ...prev, category: e.target.value }))}
                    />
                  </div>
                </div>

                {(formData.targetType === 'SELECTED' || formData.targetType === 'ROLE') && (
                  <div>
                    <Label htmlFor="targetIds">
                      {formData.targetType === 'SELECTED' ? 'User IDs (comma-separated)' : 'Role IDs (comma-separated)'}
                    </Label>
                    <Input
                      id="targetIds"
                      placeholder={formData.targetType === 'SELECTED' ? 'user-001, user-002, user-003' : 'admin, user, moderator'}
                      value={formData.targetIds}
                      onChange={(e) => setFormData(prev => ({ ...prev, targetIds: e.target.value }))}
                      required
                    />
                  </div>
                )}

                <div>
                  <Label htmlFor="scheduleType">Schedule Type</Label>
                  <Select value={formData.isImmediate ? 'immediate' : 'scheduled'} onValueChange={(value) => setFormData(prev => ({ ...prev, isImmediate: value === 'immediate' }))}>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="immediate">Publish Immediately</SelectItem>
                      <SelectItem value="scheduled">Schedule for Later</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                {!formData.isImmediate && (
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <Label htmlFor="startTime">Start Date & Time</Label>
                      <Input
                        id="startTime"
                        type="datetime-local"
                        value={formData.startTime}
                        onChange={(e) => setFormData(prev => ({ ...prev, startTime: e.target.value }))}
                        required={!formData.isImmediate}
                      />
                    </div>
                    <div>
                      <Label htmlFor="endTime">End Date & Time (optional)</Label>
                      <Input
                        id="endTime"
                        type="datetime-local"
                        value={formData.endTime}
                        onChange={(e) => setFormData(prev => ({ ...prev, endTime: e.target.value }))}
                      />
                    </div>
                  </div>
                )}

                <Button type="submit" disabled={loading || !formData.content.trim()}>
                  {loading ? 'Creating...' : 'Create Broadcast'}
                </Button>
              </form>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="manage">
          <Card>
            <CardHeader>
              <CardTitle>Manage Broadcasts</CardTitle>
              <CardDescription>
                View and manage existing broadcast messages
              </CardDescription>
              <Tabs value={manageFilter} onValueChange={setManageFilter} className="w-full">
                <TabsList className="grid w-full grid-cols-3">
                  <TabsTrigger value="all">All ({broadcasts.length})</TabsTrigger>
                  <TabsTrigger value="active">Active</TabsTrigger>
                  <TabsTrigger value="scheduled">Scheduled</TabsTrigger>
                </TabsList>
              </Tabs>
            </CardHeader>
            <CardContent>
              <div className="space-y-4 max-h-96 overflow-y-auto">
                {broadcasts.map((broadcast) => (
                  <div key={broadcast.id} className="border rounded-lg p-4 space-y-2">
                    <div className="flex items-start justify-between">
                      <div className="space-y-1">
                        <div className="flex items-center gap-2">
                          <Badge className={getPriorityColor(broadcast.priority)}>
                            {broadcast.priority}
                          </Badge>
                          <Badge className={getStatusColor(getBroadcastStatus(broadcast))}>
                            {getBroadcastStatus(broadcast)}
                          </Badge>
                          <Badge variant="outline">{broadcast.category}</Badge>
                        </div>
                        <p className="text-sm text-gray-600">{broadcast.content}</p>
                        <p className="text-xs text-gray-500">
                          From {broadcast.senderName} • {new Date(broadcast.createdAt).toLocaleString()}
                          {broadcast.isImmediate ? ' • Immediate' : ` • Scheduled`}
                          {broadcast.isImmediate ? '' : ` • Starts: ${new Date(broadcast.startTime).toLocaleString()}`}
                          {broadcast.endTime && ` • Ends: ${new Date(broadcast.endTime).toLocaleString()}`}
                        </p>
                      </div>
                      <div className="flex items-center gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            setSelectedBroadcast(broadcast);
                            setActiveTab('stats');
                          }}
                        >
                          View Stats
                        </Button>
                        {(getBroadcastStatus(broadcast) === 'ACTIVE' || getBroadcastStatus(broadcast) === 'SCHEDULED') && (
                          <Button
                            variant="destructive"
                            size="sm"
                            onClick={() => cancelBroadcast(broadcast.id)}
                          >
                            Cancel
                          </Button>
                        )}
                      </div>
                    </div>
                    <div className="flex items-center gap-4 text-sm text-gray-600">
                      <div className="flex items-center gap-1">
                        <Users className="h-4 w-4" />
                        <span>{broadcast.totalTargeted} targeted</span>
                      </div>
                      <div className="flex items-center gap-1">
                        <CheckCircle className="h-4 w-4 text-green-600" />
                        <span>{broadcast.totalDelivered} delivered</span>
                        {broadcast.totalTargeted > 0 && (
                          <span className="text-xs text-gray-500">
                            ({Math.round((broadcast.totalDelivered / broadcast.totalTargeted) * 100)}%)
                          </span>
                        )}
                      </div>
                      <div className="flex items-center gap-1">
                        <Clock className="h-4 w-4 text-blue-600" />
                        <span>{broadcast.totalRead} read</span>
                        {broadcast.totalDelivered > 0 && (
                          <span className="text-xs text-gray-500">
                            ({Math.round((broadcast.totalRead / broadcast.totalDelivered) * 100)}%)
                          </span>
                        )}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="stats">
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <Card>
              <CardHeader>
                <CardTitle>Broadcast Statistics</CardTitle>
                <CardDescription>
                  {selectedBroadcast ? `Stats for broadcast #${selectedBroadcast.id}` : 'Select a broadcast to view statistics'}
                </CardDescription>
              </CardHeader>
              <CardContent>
                {stats ? (
                  <div className="space-y-4">
                    <div className="grid grid-cols-2 gap-4">
                      <div className="text-center p-4 bg-blue-50 rounded-lg">
                        <div className="text-2xl font-bold text-blue-600">{stats.totalTargeted}</div>
                        <div className="text-sm text-gray-600">Total Targeted</div>
                      </div>
                      <div className="text-center p-4 bg-green-50 rounded-lg">
                        <div className="text-2xl font-bold text-green-600">{stats.totalDelivered}</div>
                        <div className="text-sm text-gray-600">Total Delivered</div>
                      </div>
                      <div className="text-center p-4 bg-purple-50 rounded-lg">
                        <div className="text-2xl font-bold text-purple-600">{stats.totalRead}</div>
                        <div className="text-sm text-gray-600">Total Read</div>
                      </div>
                      <div className="text-center p-4 bg-orange-50 rounded-lg">
                        <div className="text-2xl font-bold text-orange-600">
                          {((stats.totalDelivered / stats.totalTargeted) * 100).toFixed(1)}%
                        </div>
                        <div className="text-sm text-gray-600">Delivery Rate</div>
                      </div>
                    </div>
                  </div>
                ) : (
                  <Alert>
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription>
                      Select a broadcast from the Manage tab to view detailed statistics.
                    </AlertDescription>
                  </Alert>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Recent Delivery Activity</CardTitle>
                <CardDescription>
                  Real-time delivery and read activity for this broadcast
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-3 max-h-64 overflow-y-auto">
                  {deliveryDetails.length === 0 ? (
                    <div className="text-center py-4">
                      <p className="text-gray-500">No delivery activity yet</p>
                    </div>
                  ) : (
                    deliveryDetails.slice(0, 10).map((delivery) => (
                      <div key={delivery.id} className="flex items-center gap-3 text-sm">
                        {delivery.status === 'READ' ? (
                          <CheckCircle className="h-4 w-4 text-green-600" />
                        ) : (
                          <Clock className="h-4 w-4 text-blue-600" />
                        )}
                        <span>
                          {delivery.status === 'READ' 
                            ? `User ${delivery.userId} read message` 
                            : `Delivered to user ${delivery.userId}`}
                        </span>
                        {delivery.timeToRead && (
                          <span className="text-xs text-gray-500">
                            (read in {delivery.timeToRead}s)
                          </span>
                        )}
                        <span className="text-gray-500 ml-auto">
                          {new Date(delivery.deliveredAt).toLocaleTimeString()}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </CardContent>
            </Card>
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default BroadcastAdminPanel;