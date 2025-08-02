import React, { useState, useEffect, useCallback } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { useToast } from '../../hooks/use-toast';
import { UserPlus, Users, UserMinus } from 'lucide-react'; // Import necessary icons
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
          description: `Could not retrieve the list of all users from the database. ${error}`,
          variant: 'destructive',
        });
      }
    };
    fetchAllUsers();
  }, [toast]);

  const addUser = () => {
    // If no user is selected, automatically add the next available one
    const userToAdd = selectedUserId || (availableUsers.length > 0 ? availableUsers[0] : '');

    if (userToAdd && !users.includes(userToAdd)) {
      setUsers(prevUsers => [...prevUsers, userToAdd]);
      setSelectedUserId(''); // Clear selection after adding
      toast({ title: 'User Added', description: `Panel for ${userToAdd} is now active.` });
    } else if (userToAdd && users.includes(userToAdd)) {
      toast({ title: 'User Exists', description: `A panel for ${userToAdd} is already open.`, variant: 'destructive' });
    }
  };

  // START OF CHANGE: Simplified addAllUsers function for HTTP/2 environment
  const addAllUsers = () => {
    const usersToAdd = allUsers.filter(u => !users.includes(u));
    if (usersToAdd.length > 0) {
      setUsers(prevUsers => [...prevUsers, ...usersToAdd]);
      toast({
        title: 'All Users Added',
        description: `Added ${usersToAdd.length} new user panels.`,
      });
    } else {
      toast({
        title: 'No Users to Add',
        description: 'All available users already have a panel open.',
      });
    }
  };
  // END OF CHANGE

  // START OF CHANGE: Add removeAllUsers function
  const removeAllUsers = () => {
    if (users.length > 0) {
      setUsers([]);
      toast({
        title: 'All Users Removed',
        description: 'All user connection panels have been closed.',
      });
    }
  };
  // END OF CHANGE

  const removeUser = useCallback((userIdToRemove: string) => {
    setUsers(users => users.filter(user => user !== userIdToRemove));
    toast({ title: 'User Removed', description: `Panel for ${userIdToRemove} has been closed.` });
  }, [toast]);

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
            {/* START OF CHANGE: Add the "Add All" and "Remove All" buttons */}
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
                <Button variant="outline" onClick={addUser} disabled={!selectedUserId && availableUsers.length === 0}>Add User</Button>
                <Button variant="outline" onClick={addAllUsers} disabled={availableUsers.length === 0}>
                  <Users className="h-4 w-4 mr-2" />
                  Add All
                </Button>
                <Button variant="destructive" onClick={removeAllUsers} disabled={users.length === 0}>
                  <UserMinus className="h-4 w-4 mr-2" />
                  Remove All
                </Button>
            </div>
            {/* END OF CHANGE */}
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