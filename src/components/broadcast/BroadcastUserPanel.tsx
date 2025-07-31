import React, { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { useToast } from '../../hooks/use-toast';
import { UserPlus } from 'lucide-react';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import UserConnectionPanel from './UserConnectionPanel'; // Import the new component

// Main component to manage multiple user panels
const BroadcastUserPanel: React.FC = () => {
  const [users, setUsers] = useState<string[]>(['user-001']);
  const [newUserId, setNewUserId] = useState('');
  const { toast } = useToast();

  const addUser = (e: React.FormEvent) => {
    e.preventDefault();
    if (newUserId && !users.includes(newUserId)) {
      setUsers([...users, newUserId]);
      setNewUserId('');
      toast({ title: 'User Added', description: `Panel for ${newUserId} is now active.` });
    } else if (users.includes(newUserId)) {
      toast({ title: 'User Exists', description: `A panel for ${newUserId} is already open.`, variant: 'destructive' });
    }
  };

  const removeUser = (userIdToRemove: string) => {
    setUsers(users.filter(user => user !== userIdToRemove));
    toast({ title: 'User Removed', description: `Panel for ${userIdToRemove} has been closed.` });
  };

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
            <form onSubmit={addUser} className="flex items-end gap-4">
                <div className="flex-grow">
                    <Label htmlFor="newUserId">User ID</Label>
                    <Input 
                        id="newUserId"
                        placeholder="e.g., user-004"
                        value={newUserId}
                        onChange={(e) => setNewUserId(e.target.value)}
                    />
                </div>
                <Button type="submit">Add User</Button>
            </form>
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
