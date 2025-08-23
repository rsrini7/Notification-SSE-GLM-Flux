import React, { useEffect } from 'react';
import { Badge } from '../ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
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
  } = actions;

  // Initial data fetching
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

      <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-8">
        <TabsList className="grid w-full grid-cols-4 max-w-2xl mx-auto bg-gray-200">
          <TabsTrigger className='data-[state=active]:bg-yellow-400' value="create">Create Broadcast</TabsTrigger>
          <TabsTrigger className='data-[state=active]:bg-yellow-400' value="manage">Manage Broadcasts</TabsTrigger>
          <TabsTrigger className='data-[state=active]:bg-yellow-400' value="stats">Statistics</TabsTrigger>
          <TabsTrigger className='data-[state=active]:bg-yellow-400' value="dlt">DLT Management</TabsTrigger>
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