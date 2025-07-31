import React, { useState, useEffect, useCallback } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { useToast } from '../../hooks/use-toast';
import { UserPlus } from 'lucide-react';
import { Label } from '../ui/label';
import UserConnectionPanel from './UserConnectionPanel';
import { userService } from '../../services/api';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';

// Main component to manage multiple user panels
const BroadcastUserPanel: React.FC = () => {
  const [users, setUsers] = useState<string[]>(['user-001']);
  const [allUsers, setAllUsers] = useState<string[]>([]);
  const [selectedUserId, setSelectedUserId] = useState('');
  const { toast } = useToast();

  // Fetch all users from the database on component mount
  useEffect(() => {
    const fetchAllUsers = async () => {
      try {
        const userList = await userService.getAllUsers();
        setAllUsers(userList);
      } catch (error) {
        toast({
          title: 'Error Fetching Users',
          description: 'Could not retrieve the list of all users from the database.',
          variant: 'destructive',
        });
      }
    };
    fetchAllUsers();
  }, [toast]);

  const addUser = () => {
    if (selectedUserId && !users.includes(selectedUserId)) {
      setUsers([...users, selectedUserId]);
      setSelectedUserId(''); // Reset selection
      toast({ title: 'User Added', description: `Panel for ${selectedUserId} is now active.` });
    } else if (users.includes(selectedUserId)) {
      toast({ title: 'User Exists', description: `A panel for ${selectedUserId} is already open.`, variant: 'destructive' });
    }
  };

  const removeUser = useCallback((userIdToRemove: string) => {
    setUsers(users => users.filter(user => user !== userIdToRemove));
    toast({ title: 'User Removed', description: `Panel for ${userIdToRemove} has been closed.` });
  }, [toast]);
  
  // Filter out users that already have a panel open
  const availableUsers = allUsers.filter(u => !users.includes(u));

  return (
    <div className="container mx-auto p-6 space-y-8">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Broadcast User Panels</h1>
          <p className="text-gray-600">Connect multiple users to test broadcast delivery.</p>
        </div>
      </div>
      
      <Card className="border">
        <CardHeader>
            <CardTitle className="flex items-center gap-2"><UserPlus className="h-5 w-5" />Add New User Connection</CardTitle>
        </CardHeader>
        <CardContent>
            <div className="flex items-end gap-4">
                <div className="flex-grow grid gap-1.5">
                    <Label htmlFor="newUserSelect">User ID</Label>
                     <Select onValueChange={setSelectedUserId} value={selectedUserId}>
                        <SelectTrigger id="newUserSelect">
                            <SelectValue placeholder="Select a user to add..." />
                        </SelectTrigger>
                        <SelectContent>
                            {availableUsers.length > 0 ? (
                                availableUsers.map(user => (
                                    <SelectItem key={user} value={user}>{user}</SelectItem>
                                ))
                            ) : (
                                <div className="p-2 text-sm text-gray-500">No other users to add.</div>
                            )}
                        </SelectContent>
                    </Select>
                </div>
                <Button variant="outline" onClick={addUser} disabled={!selectedUserId}>Add User</Button>
            </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-6">
        {users.map(userId => (
          <UserConnectionPanel key={userId} userId={userId} onRemove={removeUser} />
        ))}
      </div>
    </div>
  );
};

export default BroadcastUserPanel;
