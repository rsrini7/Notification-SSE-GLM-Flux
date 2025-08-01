import React, { useState, useEffect, useCallback } from 'react';
import { Badge } from '../ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { useToast } from '../../hooks/use-toast';
import { broadcastService, type BroadcastMessage, type BroadcastStats, type BroadcastRequest } from '../../services/api';

// NEW: Import the new child components
import BroadcastCreationForm from './BroadcastCreationForm';
import BroadcastManagementList from './BroadcastManagementList';
import BroadcastStatisticsView from './BroadcastStatisticsView';

const BroadcastAdminPanel: React.FC = () => {
  const [broadcasts, setBroadcasts] = useState<BroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBroadcast, setSelectedBroadcast] = useState<BroadcastMessage | null>(null);
  const [stats, setStats] = useState<BroadcastStats | null>(null);
  const [deliveryDetails, setDeliveryDetails] = useState<any[]>([]);
  const [activeTab, setActiveTab] = useState('create');
  const [manageFilter, setManageFilter] = useState('all');
  const { toast } = useToast();

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

  const createBroadcast = async (payload: BroadcastRequest) => {
    setLoading(true);
    try {
      await broadcastService.createBroadcast(payload);
      await fetchBroadcasts(manageFilter);
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

        <TabsContent value="create">
            <BroadcastCreationForm
                loading={loading}
                onCreateBroadcast={createBroadcast}
            />
        </TabsContent>

        <TabsContent value="manage">
            <BroadcastManagementList
                broadcasts={broadcasts}
                manageFilter={manageFilter}
                setManageFilter={setManageFilter}
                onCancelBroadcast={cancelBroadcast}
                onSelectBroadcast={(broadcast) => {
                    setSelectedBroadcast(broadcast);
                    setActiveTab('stats');
                }}
                onRefresh={() => fetchBroadcasts(manageFilter)}
            />
        </TabsContent>

        <TabsContent value="stats">
            <BroadcastStatisticsView
                broadcasts={broadcasts}
                selectedBroadcast={selectedBroadcast}
                setSelectedBroadcast={setSelectedBroadcast}
                stats={stats}
                deliveryDetails={deliveryDetails}
                onRefresh={refreshStats}
            />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default BroadcastAdminPanel;