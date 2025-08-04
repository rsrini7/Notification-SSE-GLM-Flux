import React, { useState, useEffect, useCallback } from 'react';
import { Badge } from '../ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { useToast } from '../../hooks/use-toast';
import { broadcastService, testingService, type BroadcastMessage, type BroadcastStats, type BroadcastRequest } from '../../services/api';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Label } from '../ui/label';
import { Switch } from '../ui/switch';
import { AlertCircle } from 'lucide-react';
import BroadcastCreationForm from './BroadcastCreationForm';
import BroadcastManagementList from './BroadcastManagementList';
import BroadcastStatisticsView from './BroadcastStatisticsView';
import DltManagementPanel from './DltManagementPanel';


const BroadcastAdminPanel: React.FC = () => {
  const [broadcasts, setBroadcasts] = useState<BroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBroadcast, setSelectedBroadcast] = useState<BroadcastMessage | null>(null);
  const [stats, setStats] = useState<BroadcastStats | null>(null);
  const [deliveryDetails, setDeliveryDetails] = useState<any[]>([]);
  const [activeTab, setActiveTab] = useState('create');
  const [manageFilter, setManageFilter] = useState('all');
  const { toast } = useToast();
  
  const [isFailureModeEnabled, setFailureModeEnabled] = useState(false);

  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const status = await testingService.getKafkaFailureStatus();
        setFailureModeEnabled(status.enabled);
      } catch {
        console.error("Could not fetch testing status from backend.");
      }
    };
    fetchStatus();
  }, []);

  const handleToggleFailureMode = async (enabled: boolean) => {
    try {
      await testingService.setKafkaFailureStatus(enabled);
      setFailureModeEnabled(enabled);
      toast({
        title: `Test Mode ${enabled ? 'Enabled' : 'Disabled'}`,
        description: `Backend will now ${enabled ? 'simulate transient failures' : 'process normally'}.`,
        variant: enabled ? 'destructive' : 'default',
      });
    } catch {
      toast({
        title: 'Error',
        description: 'Failed to update test mode status.',
        variant: 'destructive',
      });
    }
  };


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
      const status = await testingService.getKafkaFailureStatus();
      setFailureModeEnabled(status.enabled);
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

      <Card className="border border-red-300 bg-red-500 dark:bg-red-900/120">
        <CardHeader>
            <CardTitle className="flex items-center gap-2 text-red-800 dark:text-red-700">
                <AlertCircle className="h-5 w-5 text-red-800 dark:text-red-900" />
                DLQ Test Mode
            </CardTitle>
        </CardHeader>
        <CardContent>
            <div className="flex items-center space-x-2">
                <Switch
                    id="kafka-failure-mode"
                    className="data-[state=checked]:bg-red-800 data-[state=unchecked]:border-red-800 data-[state=unchecked]:bg-gray-400"
                    checked={isFailureModeEnabled}
                    onCheckedChange={handleToggleFailureMode}
                    aria-label="Toggle Kafka consumer failure mode"
                />
                <Label htmlFor="kafka-failure-mode" className="flex-grow font-medium text-yellow-600 dark:text-yellow-900">
                    Enable Kafka Consumer Failure Mode
                </Label>
            </div>
            <p className="text-xs text-yellow-900 dark:text-yellow-800 mt-2">
                When enabled, messages containing "FAIL_ONCE" will be rejected to test the DLQ redrive functionality.
            </p>
        </CardContent>
      </Card>

      <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-8">
        <TabsList className="grid w-full grid-cols-4 max-w-2xl mx-auto bg-gray-200"> 
          <TabsTrigger className='data-[state=active]:bg-yellow-400' value="create">Create Broadcast</TabsTrigger>
          <TabsTrigger className='data-[state=active]:bg-yellow-400' value="manage">Manage Broadcasts</TabsTrigger>
          <TabsTrigger className='data-[state=active]:bg-yellow-400' value="stats">Statistics</TabsTrigger>
          <TabsTrigger className='data-[state=active]:bg-yellow-400' value="dlt">DLQ Management</TabsTrigger>
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
        
        <TabsContent value="dlt">
            <DltManagementPanel />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default BroadcastAdminPanel;