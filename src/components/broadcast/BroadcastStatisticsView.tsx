import React from 'react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../ui/card';
import { Button } from '../ui/button';
import { Label } from '../ui/label';
import { Alert, AlertDescription } from '../ui/alert';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { RefreshCw, AlertCircle, CheckCircle } from 'lucide-react';
import { type BroadcastMessage, type BroadcastStats } from '../../services/api';
import { Badge } from '../ui/badge';

interface BroadcastStatisticsViewProps {
    broadcasts: BroadcastMessage[];
    selectedBroadcast: BroadcastMessage | null;
    setSelectedBroadcast: (broadcast: BroadcastMessage | null) => void;
    stats: BroadcastStats | null;
    deliveryDetails: any[];
    onRefresh: () => void;
}

const BroadcastStatisticsView: React.FC<BroadcastStatisticsViewProps> = ({
    broadcasts,
    selectedBroadcast,
    setSelectedBroadcast,
    stats,
    deliveryDetails,
    onRefresh
}) => {
    return (
        <Card className="border">
            <CardHeader>
                <div className="flex items-center justify-between">
                    <CardTitle>Broadcast Statistics</CardTitle>
                    <Button variant="outline" size="sm" onClick={onRefresh}>
                        <RefreshCw className="h-4 w-4 mr-2" />
                        Refresh
                    </Button>
                </div>
                <CardDescription>View detailed statistics and delivery information</CardDescription>
            </CardHeader>
            <CardContent>
                <div className="space-y-6">
                    <div className="grid gap-1.5">
                        <Label htmlFor="broadcastSelect">Select Broadcast</Label>
                        <Select
                            value={selectedBroadcast?.id || ''}
                            onValueChange={(value) => {
                                const broadcast = broadcasts.find(b => b.id === value);
                                setSelectedBroadcast(broadcast || null);
                            }}
                        >
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
    );
};

export default BroadcastStatisticsView;