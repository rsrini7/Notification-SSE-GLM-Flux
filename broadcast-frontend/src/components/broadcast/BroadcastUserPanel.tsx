import React from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { UserPlus, Users, UserMinus } from 'lucide-react';
import { Label } from '../ui/label';
import UserConnectionPanel from './UserConnectionPanel';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { useUserPanelManager } from '../../hooks/useUserPanelManager'; // Import the new hook

// Main component to manage multiple user panels
const BroadcastUserPanel: React.FC = () => {
  // All state and logic is now managed by the custom hook
  const { state, actions } = useUserPanelManager();
  const { users, selectedUserId, availableUsers } = state;
  const { addUser, addAllUsers, removeAllUsers, removeUser, setSelectedUserId } = actions;

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