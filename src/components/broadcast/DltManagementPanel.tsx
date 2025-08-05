import React, { useState, useEffect, useCallback } from 'react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../ui/card';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { Alert, AlertDescription } from '../ui/alert';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '../ui/accordion';
import { useToast } from '../../hooks/use-toast';
import { dltService, type DltMessage } from '../../services/api';
import { AlertCircle, ArchiveRestore, RefreshCw, ServerCrash, Flame } from 'lucide-react';

const DltManagementPanel: React.FC = () => {
    const [dltMessages, setDltMessages] = useState<DltMessage[]>([]);
    const [loading, setLoading] = useState(false);
    const { toast } = useToast();

    const fetchDltMessages = useCallback(async () => {
        setLoading(true);
        try {
            const data = await dltService.getDltMessages();
            setDltMessages(data);
        } catch (error: any) {
            toast({
                title: "Error",
                description: `Failed to fetch DLT messages: ${error.response?.data?.message || error.message}`,
                variant: "destructive",
            });
        } finally {
            setLoading(false);
        }
    }, [toast]);

    useEffect(() => {
        fetchDltMessages();
    }, [fetchDltMessages]);

    const handleRedrive = async (id: string) => {
        try {
            await dltService.redriveDltMessage(id);
            toast({
                title: "Success",
                description: "Message has been sent for reprocessing.",
            });
            fetchDltMessages();
        } catch (error: any) {
            const errorMessage = error.response?.data?.message || error.message || 'An unknown error occurred.';
            toast({
                title: "Error",
                description: `Failed to redrive message: ${errorMessage}`,
                variant: "destructive",
            });
        }
    };

    // NEW HANDLER for redriving all messages
    const handleRedriveAll = async () => {
        if (!window.confirm(`Are you sure you want to redrive all ${dltMessages.length} messages?`)) {
            return;
        }
        try {
            await dltService.redriveAllDltMessages();
            toast({
                title: "Success",
                description: "All DLT messages have been sent for reprocessing.",
            });
            fetchDltMessages();
        } catch (error: any) {
            const errorMessage = error.response?.data?.message || error.message || 'An unknown error occurred.';
            toast({
                title: "Error",
                description: `Failed to redrive all messages: ${errorMessage}`,
                variant: "destructive",
            });
        }
    };

    const handlePurge = async (id: string) => {
        if (!window.confirm("Are you sure you want to permanently purge this message from both the database and Kafka? This action cannot be undone.")) {
            return;
        }
        try {
            await dltService.purgeDltMessage(id);
            toast({
                title: "Success",
                description: "Message has been purged from the database and Kafka.",
            });
            fetchDltMessages();
        } catch (error: any) {
            const errorMessage = error.response?.data?.message || error.message || 'An unknown error occurred.';
            toast({
                title: "Error",
                description: `Failed to purge message: ${errorMessage}`,
                variant: "destructive",
            });
        }
    };

    const handlePurgeAll = async () => {
        if (!window.confirm(`Are you sure you want to permanently purge all ${dltMessages.length} messages from the DLT? This action cannot be undone.`)) {
            return;
        }
        try {
            await dltService.purgeAllDltMessages();
            toast({
                title: "Success",
                description: "All DLT messages have been purged.",
            });
            fetchDltMessages();
        } catch (error: any) {
            const errorMessage = error.response?.data?.message || error.message || 'An unknown error occurred.';
            toast({
                title: "Error",
                description: `Failed to purge all messages: ${errorMessage}`,
                variant: "destructive",
            });
        }
    };
    
    const formatJsonPayload = (payload: string) => {
        try {
            const parsed = JSON.parse(payload);
            return JSON.stringify(parsed, null, 2);
        } catch {
            return payload;
        }
    }

    return (
        <Card className="border">
            <CardHeader>
                <div className="flex items-center justify-between">
                    <CardTitle className="flex items-center gap-2"><ServerCrash className="h-5 w-5" />Dead Letter Topic Management</CardTitle>
                    <div className="flex items-center gap-2">
                        <Button variant="outline" size="sm" onClick={fetchDltMessages} disabled={loading}>
                            <RefreshCw className={`h-4 w-4 mr-2 ${loading ? 'animate-spin' : ''}`} />
                            Refresh
                        </Button>
                        {/* NEW "Redrive All" button */}
                        <Button variant="outline" className="bg-blue-400 text-white" size="sm" onClick={handleRedriveAll} disabled={loading || dltMessages.length === 0}>
                            <ArchiveRestore className="h-4 w-4 mr-2" />
                            Redrive All
                        </Button>
                        <Button variant="outline" className="bg-red-400 text-white" size="sm" onClick={handlePurgeAll} disabled={loading || dltMessages.length === 0}>
                            <Flame className="h-4 w-4 mr-2" />
                            Purge All
                        </Button>
                    </div>
                </div>
                <CardDescription>View, reprocess, or delete messages that failed processing.</CardDescription>
            </CardHeader>
            <CardContent>
                <div className="space-y-4">
                    {dltMessages.length === 0 ? (
                        <Alert>
                            <AlertCircle className="h-4 w-4" />
                            <AlertDescription>The Dead Letter Topic is currently empty. All messages have been processed successfully.</AlertDescription>
                        </Alert>
                    ) : (
                        <Accordion type="single" collapsible className="w-full">
                            {dltMessages.map((msg) => (
                                <AccordionItem value={msg.id} key={msg.id}>
                                    <AccordionTrigger>
                                        <div className="flex items-center justify-between w-full pr-4">
                                            <div className="flex flex-col items-start text-left">
                                                <span className="font-semibold truncate max-w-xs md:max-w-md">{msg.exceptionMessage}</span>
                                                <span className="text-xs text-gray-500">
                                                    Failed at: {new Date(msg.failedAt).toLocaleString()}
                                                </span>
                                            </div>
                                            <Badge variant="secondary">Failed</Badge>
                                        </div>
                                    </AccordionTrigger>
                                    <AccordionContent>
                                        <div className="space-y-4 p-2 bg-gray-50 rounded-md">
                                            <div className="text-xs text-gray-600">
                                                <p><strong>Message ID:</strong> {msg.id}</p>
                                                <p><strong>Original Topic:</strong> {msg.originalTopic}</p>
                                            </div>
                                            <div>
                                                <h4 className="font-semibold text-sm mb-1">Original Payload:</h4>
                                                <pre className="text-xs bg-black text-white p-3 rounded-md overflow-x-auto">
                                                    <code>{formatJsonPayload(msg.originalMessagePayload)}</code>
                                                </pre>
                                            </div>
                                            <div className="flex items-center gap-2">
                                                <Button size="sm" onClick={() => handleRedrive(msg.id)}>
                                                    <ArchiveRestore className="h-4 w-4 mr-2" />
                                                    Redrive
                                                </Button>
                                                {/* START OF CHANGE: Remove the single "Delete" button and replace it with "Purge" */}
                                                <Button variant="destructive" size="sm" onClick={() => handlePurge(msg.id)}>
                                                    <Flame className="h-4 w-4 mr-2" />
                                                    Purge
                                                </Button>
                                                {/* END OF CHANGE */}
                                            </div>
                                        </div>
                                    </AccordionContent>
                                </AccordionItem>
                            ))}
                        </Accordion>
                    )}
                </div>
            </CardContent>
        </Card>
    );
};

export default DltManagementPanel;