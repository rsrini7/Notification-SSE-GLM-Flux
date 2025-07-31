import React, { useState, useEffect, useCallback } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Textarea } from '../ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Badge } from '../ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Alert, AlertDescription } from '../ui/alert';
import { useToast } from '../../hooks/use-toast';
import { Send, CheckCircle, AlertCircle, CalendarClock, Ban, RefreshCw } from 'lucide-react';
import {
  broadcastService,
  type BroadcastMessage,
  type BroadcastStats,
  type BroadcastRequest
} from '../../services/api';

const BroadcastAdminPanel: React.FC = () => {
  const [broadcasts, setBroadcasts] = useState<BroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBroadcast, setSelectedBroadcast] = useState<BroadcastMessage | null>(null);
  const [stats, setStats] = useState<BroadcastStats | null>(null);
  const [deliveryDetails, setDeliveryDetails] = useState<any[]>([]);
  const [activeTab, setActiveTab] = useState('create');
  const [manageFilter, setManageFilter] = useState('all');
  const { toast } = useToast();

  const [formData, setFormData] = useState({
    senderId: 'admin-001',
    senderName: 'System Administrator',
    content: '',
    targetType: 'ALL',
    targetIds: '',
    priority: 'NORMAL',
    category: 'GENERAL',
    isImmediate: true,
    scheduledAt: '',
    expiresAt: ''
  });

  const fetchBroadcasts = useCallback(async (filter = 'all') => {
    setLoading(true);
    try {
      const data = await broadcastService.getBroadcasts(filter);
      setBroadcasts(data);
    } catch {
      toast({
        title: 'Error',
        description: 'Failed to fetch broadcasts',
        variant: 'destructive',
      });
    } finally {
      setLoading(false);
    }
  }, [toast]);

  const fetchBroadcastStats = useCallback(async (broadcastId: string) => {
    try {
      const data = await broadcastService.getBroadcastStats(broadcastId);
      setStats(data);
    } catch {
      toast({
        title: 'Error',
        description: 'Failed to fetch broadcast statistics',
        variant: 'destructive',
      });
    }
  }, [toast]);

  const fetchDeliveryDetails = useCallback(async (broadcastId: string) => {
    try {
      const data = await broadcastService.getBroadcastDeliveries(broadcastId);
      setDeliveryDetails(data);
    } catch (error) {
      console.error('Error fetching delivery details:', error);
      setDeliveryDetails([]);
    }
  }, []);

  const createBroadcast = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      const payload: BroadcastRequest = {
        senderId: formData.senderId,
        senderName: formData.senderName,
        content: formData.content,
        targetType: formData.targetType,
        targetIds: formData.targetType === 'ALL' ? [] : formData.targetIds.split(',').map(id => id.trim()),
        priority: formData.priority,
        category: formData.category,
        scheduledAt: formData.isImmediate ? undefined : new Date(formData.scheduledAt).toISOString(),
        expiresAt: formData.expiresAt ? new Date(formData.expiresAt).toISOString() : undefined,
        isImmediate: formData.isImmediate,
      };

      await broadcastService.createBroadcast(payload);
      
      await fetchBroadcasts(manageFilter);
      
      setFormData({
        senderId: 'admin-001',
        senderName: 'System Administrator',
        content: '',
        targetType: 'ALL',
        targetIds: '',
        priority: 'NORMAL',
        category: 'GENERAL',
        isImmediate: true,
        scheduledAt: '',
        expiresAt: ''
      });

      toast({
        title: 'Success',
        description: 'Broadcast created successfully',
      });
      setActiveTab('manage');
    } catch {
      toast({
        title: 'Error',
        description: 'Failed to create broadcast',
        variant: 'destructive',
      });
    } finally {
      setLoading(false);
    }
  };

  const cancelBroadcast = async (broadcastId: string) => {
    try {
      await broadcastService.cancelBroadcast(broadcastId);
      await fetchBroadcasts(manageFilter);
      toast({
        title: 'Success',
        description: 'Broadcast cancelled successfully',
      });
    } catch {
      toast({
        title: 'Error',
        description: 'Failed to cancel broadcast',
        variant: 'destructive',
      });
    }
  };

  useEffect(() => {
    fetchBroadcasts(manageFilter);
  }, [manageFilter, fetchBroadcasts]);

  useEffect(() => {
    if (selectedBroadcast) {
      fetchBroadcastStats(selectedBroadcast.id);
      fetchDeliveryDetails(selectedBroadcast.id);
    } else {
      setStats(null);
      setDeliveryDetails([]);
    }
  }, [selectedBroadcast, fetchBroadcastStats, fetchDeliveryDetails]);

  const getPriorityColor = (priority: string) => {
    switch (priority) {
      case 'URGENT': return 'bg-red-200 text-red-900';
      case 'HIGH': return 'bg-orange-200 text-orange-900';
      case 'NORMAL': return 'bg-blue-200 text-blue-900';
      case 'LOW': return 'bg-gray-200 text-gray-900';
      default: return 'bg-gray-200 text-gray-900';
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'ACTIVE': return 'bg-green-200 text-green-900';
      case 'EXPIRED': return 'bg-yellow-200 text-yellow-900';
      case 'CANCELLED': return 'bg-red-200 text-red-900';
      case 'SCHEDULED': return 'bg-purple-200 text-purple-900';
      default: return 'bg-gray-200 text-gray-900';
    }
  };

  const refreshStats = useCallback(() => {
    fetchBroadcasts('all');
    if (selectedBroadcast) {
        fetchBroadcastStats(selectedBroadcast.id);
        fetchDeliveryDetails(selectedBroadcast.id);
    }
    toast({
        title: "Refreshed",
        description: "Statistics and broadcast data have been updated.",
    });
  }, [selectedBroadcast, fetchBroadcasts, fetchBroadcastStats, fetchDeliveryDetails, toast]);


  return (
    <div className="container mx-auto p-6 space-y-8">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Broadcast Admin Panel</h1>
          <p className="text-gray-600">Manage and send broadcast messages to users</p>
        </div>
        <Badge variant="outline" className="text-sm">
          {broadcasts.length} Total Broadcasts
        </Badge>
      </div>

      <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-8">
        <TabsList className="grid w-full grid-cols-3">
          <TabsTrigger value="create">Create Broadcast</TabsTrigger>
          <TabsTrigger value="manage">Manage Broadcasts</TabsTrigger>
          <TabsTrigger value="stats">Statistics</TabsTrigger>
        </TabsList>

        <TabsContent value="create" className="overflow-visible">
          <Card className="border z-50 overflow-visible">
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
                    <Input id="senderId" value={formData.senderId} onChange={(e) => setFormData(prev => ({ ...prev, senderId: e.target.value }))} required />
                  </div>
                  <div>
                    <Label htmlFor="senderName">Sender Name</Label>
                    <Input id="senderName" value={formData.senderName} onChange={(e) => setFormData(prev => ({ ...prev, senderName: e.target.value }))} required />
                  </div>
                </div>

                <div>
                  <Label htmlFor="content">Message Content</Label>
                  <Textarea id="content" placeholder="Enter your broadcast message..." value={formData.content} onChange={(e) => setFormData(prev => ({ ...prev, content: e.target.value }))} rows={4} required />
                </div>

                <div className="grid grid-cols-3 gap-4 overflow-visible">
                  <div>
                    <Label htmlFor="targetType">Target Type</Label>
                    <Select value={formData.targetType} onValueChange={(value) => setFormData(prev => ({ ...prev, targetType: value }))}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent position="popper" sideOffset={20}>
                        <SelectItem value="ALL">All Users</SelectItem>
                        <SelectItem value="SELECTED">Selected Users</SelectItem>
                        <SelectItem value="ROLE">By Role</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div>
                    <Label htmlFor="priority">Priority</Label>
                    <Select value={formData.priority} onValueChange={(value) => setFormData(prev => ({ ...prev, priority: value }))}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent position="popper" sideOffset={20}>
                        <SelectItem value="LOW">Low</SelectItem>
                        <SelectItem value="NORMAL">Normal</SelectItem>
                        <SelectItem value="HIGH">High</SelectItem>
                        <SelectItem value="URGENT">Urgent</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div>
                    <Label htmlFor="category">Category</Label>
                    <Input id="category" value={formData.category} onChange={(e) => setFormData(prev => ({ ...prev, category: e.target.value }))} />
                  </div>
                </div>

                {(formData.targetType === 'SELECTED' || formData.targetType === 'ROLE') && (
                  <div>
                    <Label htmlFor="targetIds">{formData.targetType === 'SELECTED' ? 'User IDs (comma-separated)' : 'Role IDs (comma-separated)'}</Label>
                    <Input id="targetIds" placeholder={formData.targetType === 'SELECTED' ? 'user-001, user-002' : 'admin, moderator'} value={formData.targetIds} onChange={(e) => setFormData(prev => ({ ...prev, targetIds: e.target.value }))} required />
                  </div>
                )}

                <div className="grid grid-cols-2 gap-4">
                    <div>
                        <Label htmlFor="scheduleType">Schedule Type</Label>
                        <Select value={formData.isImmediate ? 'immediate' : 'scheduled'} onValueChange={(value) => setFormData(prev => ({ ...prev, isImmediate: value === 'immediate' }))}>
                            <SelectTrigger><SelectValue /></SelectTrigger>
                            <SelectContent position="popper" sideOffset={20}>
                            <SelectItem value="immediate">Publish Immediately</SelectItem>
                            <SelectItem value="scheduled">Schedule for Later</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>
                    <div>
                        <Label htmlFor="expiresAt">Expires At (optional)</Label>
                        <Input id="expiresAt" type="datetime-local" value={formData.expiresAt} onChange={(e) => setFormData(prev => ({ ...prev, expiresAt: e.target.value }))} />
                    </div>
                </div>

                {!formData.isImmediate && (
                  <div>
                    <Label htmlFor="scheduledAt">Start Date & Time</Label>
                    <Input id="scheduledAt" type="datetime-local" value={formData.scheduledAt} onChange={(e) => setFormData(prev => ({ ...prev, scheduledAt: e.target.value }))} required={!formData.isImmediate} />
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
          <Card className="border">
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Manage Broadcasts</CardTitle>
                <Button variant="outline" size="sm" onClick={() => fetchBroadcasts(manageFilter)}>
                    <RefreshCw className="h-4 w-4 mr-2" />
                    Refresh
                </Button>
              </div>
              <CardDescription>View and manage existing broadcast messages</CardDescription>
              <Tabs value={manageFilter} onValueChange={setManageFilter} className="w-full">
                <TabsList className="grid w-full grid-cols-3">
                  <TabsTrigger value="all">All</TabsTrigger>
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
                        <div className="flex items-center gap-2 flex-wrap">
                          <Badge className={getPriorityColor(broadcast.priority)}>{broadcast.priority}</Badge>
                          <Badge variant="outline">{broadcast.category}</Badge>
                          <Badge className={getStatusColor(broadcast.status)}>{broadcast.status}</Badge>
                        </div>
                        <p className="text-sm text-gray-800 pt-1">{broadcast.content}</p>
                        <p className="text-xs text-gray-500">
                          From {broadcast.senderName} • Created: {new Date(broadcast.createdAt).toLocaleString()}
                        </p>
                        <div className="flex items-center gap-4 text-xs text-gray-500 mt-1 flex-wrap">
                          {broadcast.scheduledAt && (
                            <div className="flex items-center gap-1">
                              <CalendarClock className="h-3 w-3" />
                              <span>Starts: {new Date(broadcast.scheduledAt).toLocaleString()}</span>
                            </div>
                          )}
                          {broadcast.expiresAt && (
                            <div className="flex items-center gap-1">
                              <Ban className="h-3 w-3" />
                              <span>Expires: {new Date(broadcast.expiresAt).toLocaleString()}</span>
                            </div>
                          )}
                        </div>
                      </div>
                      {broadcast.status !== 'CANCELLED' && broadcast.status !== 'EXPIRED' && (
                        <Button variant="outline" size="sm" onClick={() => cancelBroadcast(broadcast.id)}>Cancel</Button>
                      )}
                    </div>
                    <div className="grid grid-cols-3 gap-4 text-xs pt-2 border-t border-gray-100">
                      <div><span className="text-gray-500">Targeted:</span><span className="ml-1 font-medium">{broadcast.totalTargeted}</span></div>
                      <div><span className="text-gray-500">Delivered:</span><span className="ml-1 font-medium">{broadcast.totalDelivered}</span></div>
                      <div><span className="text-gray-500">Read:</span><span className="ml-1 font-medium">{broadcast.totalRead}</span></div>
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="stats">
          <Card className="border">
            <CardHeader>
                <div className="flex items-center justify-between">
                    <CardTitle>Broadcast Statistics</CardTitle>
                    <Button variant="outline" size="sm" onClick={refreshStats}>
                        <RefreshCw className="h-4 w-4 mr-2" />
                        Refresh
                    </Button>
                </div>
              <CardDescription>View detailed statistics and delivery information</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-6">
                <div>
                  <Label htmlFor="broadcastSelect">Select Broadcast</Label>
                  <Select value={selectedBroadcast?.id || ''} onValueChange={(value) => {
                    const broadcast = broadcasts.find(b => b.id === value);
                    setSelectedBroadcast(broadcast || null);
                  }}>
                    <SelectTrigger><SelectValue placeholder="Choose a broadcast to view statistics" /></SelectTrigger>
                    <SelectContent>
                      {broadcasts.map((broadcast) => (
                        <SelectItem key={broadcast.id} value={broadcast.id}>
                          {broadcast.content.substring(0, 50)}... ({new Date(broadcast.createdAt).toLocaleDateString()})
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                {selectedBroadcast && stats && (
                  <div className="space-y-6">
                    <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
                      <Card><CardHeader className="pb-2"><CardTitle className="text-sm">Total Targeted</CardTitle></CardHeader><CardContent><div className="text-2xl font-bold">{stats.totalTargeted}</div></CardContent></Card>
                      <Card><CardHeader className="pb-2"><CardTitle className="text-sm">Total Delivered</CardTitle></CardHeader><CardContent><div className="text-2xl font-bold">{stats.totalDelivered}</div></CardContent></Card>
                      <Card><CardHeader className="pb-2"><CardTitle className="text-sm">Total Read</CardTitle></CardHeader><CardContent><div className="text-2xl font-bold">{stats.totalRead}</div></CardContent></Card>
                      <Card><CardHeader className="pb-2"><CardTitle className="text-sm">Delivery Rate</CardTitle></CardHeader><CardContent><div className="text-2xl font-bold">{(stats.deliveryRate * 100).toFixed(1)}%</div></CardContent></Card>
                    </div>

                    <Card>
                      <CardHeader><CardTitle>Delivery Details</CardTitle></CardHeader>
                      <CardContent>
                        <div className="space-y-2 max-h-64 overflow-y-auto">
                          {deliveryDetails.map((detail, index) => (
                            <div key={index} className="flex justify-between items-center p-2 border-b">
                              <span className="text-sm">{detail.userId}</span>
                              <div className="flex items-center gap-2">
                                <Badge variant={detail.deliveryStatus === 'DELIVERED' ? 'default' : 'secondary'}>{detail.deliveryStatus}</Badge>
                                {detail.readStatus === 'READ' && (<CheckCircle className="h-4 w-4 text-green-600" />)}
                              </div>
                            </div>
                          ))}
                        </div>
                      </CardContent>
                    </Card>
                  </div>
                )}

                {!selectedBroadcast && (
                  <Alert>
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription>Select a broadcast to view its statistics and delivery details.</AlertDescription>
                  </Alert>
                )}
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default BroadcastAdminPanel;
