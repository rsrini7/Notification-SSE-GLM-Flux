import React, { useState } from 'react';
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
        isImmediate: true,
        scheduledAt: '',
        expiresAt: ''
    });

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
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
        onCreateBroadcast(payload);
        // Reset form after submission is handled by parent
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
    };
    
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
                                    <SelectItem value="ALL">All Users</SelectItem>
                                    <SelectItem value="SELECTED">Selected Users</SelectItem>
                                    <SelectItem value="ROLE">By Role</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid gap-1.5">
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
                        <div className="grid gap-1.5">
                            <Label htmlFor="category">Category</Label>
                            <Input id="category" value={formData.category} onChange={(e) => setFormData(prev => ({ ...prev, category: e.target.value }))} />
                        </div>
                    </div>

                    {(formData.targetType === 'SELECTED' || formData.targetType === 'ROLE') && (
                        <div className="grid gap-1.5">
                            <Label htmlFor="targetIds">{formData.targetType === 'SELECTED' ? 'User IDs (comma-separated)' : 'Role IDs (comma-separated)'}</Label>
                            <Input id="targetIds" placeholder={formData.targetType === 'SELECTED' ? 'user-001, user-002' : 'admin, moderator'} value={formData.targetIds} onChange={(e) => setFormData(prev => ({ ...prev, targetIds: e.target.value }))} required />
                        </div>
                    )}

                    <div className="grid grid-cols-2 gap-4">
                        <div className="grid gap-1.5">
                            <Label htmlFor="scheduleType">Schedule Type</Label>
                            <Select value={formData.isImmediate ? 'immediate' : 'scheduled'} onValueChange={(value) => setFormData(prev => ({ ...prev, isImmediate: value === 'immediate' }))}>
                                <SelectTrigger><SelectValue /></SelectTrigger>
                                <SelectContent position="popper" sideOffset={20}>
                                    <SelectItem value="immediate">Publish Immediately</SelectItem>
                                    <SelectItem value="scheduled">Schedule for Later</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="expiresAt">Expires At (optional)</Label>
                            <Input id="expiresAt" type="datetime-local" value={formData.expiresAt} onChange={(e) => setFormData(prev => ({ ...prev, expiresAt: e.target.value }))} />
                        </div>
                    </div>

                    {!formData.isImmediate && (
                        <div className="grid gap-1.5">
                            <Label htmlFor="scheduledAt">Start Date & Time</Label>
                            <Input id="scheduledAt" type="datetime-local" value={formData.scheduledAt} onChange={(e) => setFormData(prev => ({ ...prev, scheduledAt: e.target.value }))} required={!formData.isImmediate} />
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