import { useState, useCallback } from 'react';
import { useToast } from '@/hooks/use-toast';
import { broadcastService, testingService, type BroadcastMessage, type BroadcastStats, type BroadcastRequest } from '../services/api';

export const useBroadcastManagement = () => {
  const [broadcasts, setBroadcasts] = useState<BroadcastMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBroadcast, setSelectedBroadcast] = useState<BroadcastMessage | null>(null);
  const [stats, setStats] = useState<BroadcastStats | null>(null);
  const [deliveryDetails, setDeliveryDetails] = useState<any[]>([]);
  const [activeTab, setActiveTab] = useState('create');
  const [manageFilter, setManageFilter] = useState('all');
  const [isFailureModeEnabled, setFailureModeEnabled] = useState(false);
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
  
  const fetchFailureModeStatus = useCallback(async () => {
      try {
        const status = await testingService.getKafkaFailureStatus();
        setFailureModeEnabled(status.enabled);
      } catch {
        console.error("Could not fetch testing status from backend.");
      }
  }, []);

  return {
    state: {
      broadcasts,
      loading,
      selectedBroadcast,
      stats,
      deliveryDetails,
      activeTab,
      manageFilter,
      isFailureModeEnabled,
    },
    actions: {
      fetchBroadcasts,
      fetchBroadcastStats,
      fetchDeliveryDetails,
      createBroadcast,
      cancelBroadcast,
      refreshStats,
      setSelectedBroadcast,
      setActiveTab,
      setManageFilter,
      handleToggleFailureMode,
      fetchFailureModeStatus,
    }
  };
};