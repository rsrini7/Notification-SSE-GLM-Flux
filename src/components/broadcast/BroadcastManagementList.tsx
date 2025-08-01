import React from 'react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../ui/card';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { Tabs, TabsList, TabsTrigger } from '../ui/tabs';
import { RefreshCw, CalendarClock, Ban, BarChart3 } from 'lucide-react';
import { type BroadcastMessage } from '../../services/api';

interface BroadcastManagementListProps {
    broadcasts: BroadcastMessage[];
    manageFilter: string;
    setManageFilter: (filter: string) => void;
    onCancelBroadcast: (id: string) => void;
    onSelectBroadcast: (broadcast: BroadcastMessage) => void;
    onRefresh: () => void;
}

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

const BroadcastManagementList: React.FC<BroadcastManagementListProps> = ({
    broadcasts,
    manageFilter,
    setManageFilter,
    onCancelBroadcast,
    onSelectBroadcast,
    onRefresh
}) => {
    return (
        <Card className="border">
            <CardHeader>
                <div className="flex items-center justify-between">
                    <CardTitle>Manage Broadcasts</CardTitle>
                    <Button variant="outline" size="sm" onClick={onRefresh}>
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
                                <div className="flex items-center gap-2 flex-shrink-0">
                                    <Button variant="outline" size="sm" onClick={() => onSelectBroadcast(broadcast)}>
                                        <BarChart3 className="h-4 w-4" />
                                    </Button>
                                    {broadcast.status !== 'CANCELLED' && broadcast.status !== 'EXPIRED' && (
                                        <Button variant="outline" size="sm" onClick={() => onCancelBroadcast(broadcast.id)}>Cancel</Button>
                                    )}
                                </div>
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
    );
};

export default BroadcastManagementList;