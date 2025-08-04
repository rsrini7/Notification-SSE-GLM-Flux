import React, { useEffect } from 'react';
import { Badge } from '../ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Label } from '../ui/label';
import { Switch } from '../ui/switch';
import { AlertCircle } from 'lucide-react';
import BroadcastCreationForm from './BroadcastCreationForm';
import BroadcastManagementList from './BroadcastManagementList';
import BroadcastStatisticsView from './BroadcastStatisticsView';
import DltManagementPanel from './DltManagementPanel';
import { useBroadcastManagement } from '../../hooks/useBroadcastManagement'; // Import the new hook

const BroadcastAdminPanel: React.FC = () => {
  // All state and logic is now managed by the custom hook
  const { state, actions } = useBroadcastManagement();
  const {
    broadcasts,
    loading,
    selectedBroadcast,
    stats,
    deliveryDetails,
    activeTab,
    manageFilter,
    isFailureModeEnabled,
  } = state;
  const {
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
  } = actions;

  // Initial data fetching
  useEffect(() => {
    fetchFailureModeStatus();
  }, [fetchFailureModeStatus]);
  
  useEffect(() => {
    fetchBroadcasts(manageFilter);
  }, [manageFilter, fetchBroadcasts]);

  // Fetch stats and details when a broadcast is selected
  useEffect(() => {
    if (selectedBroadcast) {
      fetchBroadcastStats(selectedBroadcast.id);
      fetchDeliveryDetails(selectedBroadcast.id);
    } else {
      // Clear stats and details if no broadcast is selected
      // This logic can be moved inside the hook if preferred
    }
  }, [selectedBroadcast, fetchBroadcastStats, fetchDeliveryDetails]);

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
            When enabled, the next created broadcast will fail processing to test DLT functionality.
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