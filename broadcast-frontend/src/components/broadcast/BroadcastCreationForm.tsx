import React, { useState, useEffect } from 'react'; // Import useEffect
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../ui/card';
import { Button } from '../ui/button';
import { Textarea } from '../ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Send } from 'lucide-react';
import { type BroadcastRequest } from '../../services/api';

interface BroadcastCreationFormProps {
    loading: boolean;
    onCreateBroadcast: (payload: BroadcastRequest) => void;
}

const BroadcastCreationForm: React.FC<BroadcastCreationFormProps> = ({ loading, onCreateBroadcast }) => {
    const [formData, setFormData] = useState({
        senderId: 'admin-001',
        senderName: 'System Administrator',
        content: '',
        targetType: 'ALL',
        targetIds: '',
        priority: 'NORMAL',
        category: 'GENERAL',
        scheduleType: 'immediate', // Changed from isImmediate boolean
        scheduledAt: '',
        expiresAt: ''
    });

    // This effect now handles the logic for both selecting and de-selecting "Fire and Forget"
    useEffect(() => {
        if (formData.scheduleType === 'fireAndForget') {
            setFormData(prev => ({
                ...prev,
                category: 'Force Logoff',
                priority: 'URGENT',
                content: 'FireAndForget',
                scheduledAt: '',
                expiresAt: '' // Clear expiresAt initially; it will be set on submit
            }));
        } else if ((formData.targetType === 'ALL' || formData.targetType === 'ROLE' || formData.targetType === 'PRODUCT') && formData.scheduleType === 'fireAndForget') {
             setFormData(prev => ({
                ...prev,
                scheduleType: 'immediate' // Reset to default
             }));
        } else {
            // If the user switches away from Fire and Forget, reset the category and clear the expiry.
            if (formData.category === 'Force Logoff') {
                setFormData(prev => ({
                    ...prev,
                    category: 'GENERAL',
                    expiresAt: ''
                }));
            }
        }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [formData.scheduleType, formData.targetType]);


    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();

        // Calculate expiresAt for Fire and Forget at the moment of submission
        let finalExpiresAt = formData.expiresAt ? new Date(formData.expiresAt).toISOString() : undefined;
        if (formData.scheduleType === 'fireAndForget') {
            finalExpiresAt = new Date(Date.now() + 10000).toISOString();
        }

        const payload: BroadcastRequest = {
            senderId: formData.senderId,
            senderName: formData.senderName,
            content: formData.content,
            targetType: formData.targetType,
            targetIds: formData.targetType === 'ALL' ? [] : formData.targetIds.split(',').map(id => id.trim()),
            priority: formData.priority,
            category: formData.category,
            scheduledAt: formData.scheduleType === 'scheduled' ? new Date(formData.scheduledAt).toISOString() : undefined,
            expiresAt: finalExpiresAt,
            isImmediate: formData.scheduleType !== 'scheduled',
            fireAndForget: formData.scheduleType === 'fireAndForget',
        };
        onCreateBroadcast(payload);
        setFormData({
            senderId: 'admin-001',
            senderName: 'System Administrator',
            content: '',
            targetType: 'ALL',
            targetIds: '',
            priority: 'NORMAL',
            category: 'GENERAL',
            scheduleType: 'immediate',
            scheduledAt: '',
            expiresAt: ''
        });
    };
    
    const isFireAndForget = formData.scheduleType === 'fireAndForget';
    
    return (
        <Card className="border z-50 overflow-visible">
            <CardHeader>
                <CardTitle className="flex items-center gap-2"><Send className="h-5 w-5" />Create New Broadcast</CardTitle>
                <CardDescription>Send a message to all users or selected groups</CardDescription>
            </CardHeader>
            <CardContent>
                <form onSubmit={handleSubmit} className="space-y-4">
                    <div className="grid grid-cols-2 gap-4">
                        <div className="grid gap-1.5">
                            <Label htmlFor="senderId">Sender ID</Label>
                            <Input id="senderId" value={formData.senderId} onChange={(e) => setFormData(prev => ({ ...prev, senderId: e.target.value }))} required />
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="senderName">Sender Name</Label>
                            <Input id="senderName" value={formData.senderName} onChange={(e) => setFormData(prev => ({ ...prev, senderName: e.target.value }))} required />
                        </div>
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="content">Message Content</Label>
                        <Textarea id="content" placeholder="Enter your broadcast message..." value={formData.content} onChange={(e) => setFormData(prev => ({ ...prev, content: e.target.value }))} rows={4} required />
                    </div>

                    <div className="grid grid-cols-3 gap-4 overflow-visible">
                        <div className="grid gap-1.5">
                            <Label htmlFor="targetType">Target Type</Label>
                            <Select value={formData.targetType} onValueChange={(value) => setFormData(prev => ({ ...prev, targetType: value }))}>
                                <SelectTrigger><SelectValue /></SelectTrigger>
                                <SelectContent position="popper" sideOffset={20}>
                                    <SelectItem value="ALL" disabled={formData.scheduleType === 'fireAndForget'}>All Users</SelectItem>
                                    <SelectItem value="SELECTED">Selected Users</SelectItem>
                                    <SelectItem value="ROLE" disabled={formData.scheduleType === 'fireAndForget'}>By Role</SelectItem>
                                    <SelectItem value="PRODUCT" disabled={formData.scheduleType === 'fireAndForget'}>By Product</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="priority">Priority</Label>
                            <Select value={formData.priority} onValueChange={(value) => setFormData(prev => ({ ...prev, priority: value }))} disabled={isFireAndForget}>
                                <SelectTrigger><SelectValue /></SelectTrigger>
                                <SelectContent position="popper" sideOffset={20}>
                                    <SelectItem value="LOW">Low</SelectItem>
                                    <SelectItem value="NORMAL">Normal</SelectItem>
                                    <SelectItem value="HIGH">High</SelectItem>
                                    <SelectItem value="URGENT">Urgent</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="category">Category</Label>
                            <Input id="category" value={formData.category} onChange={(e) => setFormData(prev => ({ ...prev, category: e.target.value }))} disabled={isFireAndForget} />
                        </div>
                    </div>

                    {(formData.targetType === 'SELECTED' || formData.targetType === 'ROLE' || formData.targetType === 'PRODUCT') && (
                        <div className="grid gap-1.5">
                            <Label htmlFor="targetIds">{
                                formData.targetType === 'SELECTED' ? 'User IDs (comma-separated)' :
                                formData.targetType === 'ROLE' ? 'Role IDs (comma-separated)' :
                                'Product IDs (e.g., Payments)'
                            }</Label>
                            <Input id="targetIds" placeholder={formData.targetType === 'SELECTED' ? 'user-001, user-002' : 'admin, moderator'} value={formData.targetIds} onChange={(e) => setFormData(prev => ({ ...prev, targetIds: e.target.value }))} required />
                        </div>
                    )}

                    <div className="grid grid-cols-2 gap-4">
                        <div className="grid gap-1.5">
                            <Label htmlFor="scheduleType">Schedule Type</Label>
                            <Select value={formData.scheduleType} onValueChange={(value) => setFormData(prev => ({ ...prev, scheduleType: value }))}>
                                <SelectTrigger><SelectValue /></SelectTrigger>
                                <SelectContent position="popper" sideOffset={20}>
                                    <SelectItem value="immediate">Publish Immediately</SelectItem>
                                    <SelectItem value="scheduled">Schedule for Later</SelectItem>
                                    <SelectItem value="fireAndForget" disabled={formData.targetType === 'ALL' || formData.targetType === 'ROLE' || formData.targetType === 'PRODUCT'}>Fire and Forget</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="expiresAt">Expires At (optional)</Label>
                            <Input id="expiresAt" type="datetime-local" value={formData.expiresAt} onChange={(e) => setFormData(prev => ({ ...prev, expiresAt: e.target.value }))} disabled={isFireAndForget} />
                        </div>
                    </div>

                    {formData.scheduleType === 'scheduled' && (
                        <div className="grid gap-1.5">
                            <Label htmlFor="scheduledAt">Start Date & Time</Label>
                            <Input id="scheduledAt" type="datetime-local" value={formData.scheduledAt} onChange={(e) => setFormData(prev => ({ ...prev, scheduledAt: e.target.value }))} required={formData.scheduleType === 'scheduled'} />
                        </div>
                    )}

                    <Button variant="outline" type="submit" disabled={loading || !formData.content.trim()}>
                        {loading ? 'Creating...' : 'Create Broadcast'}
                    </Button>
                </form>
            </CardContent>
        </Card>
    );
};

export default BroadcastCreationForm;