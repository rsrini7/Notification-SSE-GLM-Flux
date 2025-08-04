import { useState, useEffect, useCallback } from 'react';
import { useToast } from '@/hooks/use-toast';
import { userService } from '../services/api';

export const useUserPanelManager = () => {
  const [users, setUsers] = useState<string[]>(['user-001']);
  const [allUsers, setAllUsers] = useState<string[]>([]);
  const [selectedUserId, setSelectedUserId] = useState('');
  const { toast } = useToast();

  useEffect(() => {
    const fetchAllUsers = async () => {
      try {
        const userList = await userService.getAllUsers();
        setAllUsers(userList);
      } catch (error) {
        toast({
          title: 'Error Fetching Users',
          description: `Could not retrieve the list of all users. ${error}`,
          variant: 'destructive',
        });
      }
    };
    fetchAllUsers();
  }, [toast]);
  
  const availableUsers = allUsers.filter(u => !users.includes(u));

  const addUser = () => {
    const userToAdd = selectedUserId || (availableUsers.length > 0 ? availableUsers[0] : '');

    if (userToAdd && !users.includes(userToAdd)) {
      setUsers(prevUsers => [...prevUsers, userToAdd]);
      setSelectedUserId(''); // Clear selection after adding
      toast({ title: 'User Added', description: `Panel for ${userToAdd} is now active.` });
    } else if (userToAdd && users.includes(userToAdd)) {
      toast({ title: 'User Exists', description: `A panel for ${userToAdd} is already open.`, variant: 'destructive' });
    }
  };

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

  const removeAllUsers = () => {
    if (users.length > 0) {
      setUsers([]);
      toast({
        title: 'All Users Removed',
        description: 'All user connection panels have been closed.',
      });
    }
  };

  const removeUser = useCallback((userIdToRemove: string) => {
    setUsers(users => users.filter(user => user !== userIdToRemove));
    toast({ title: 'User Removed', description: `Panel for ${userIdToRemove} has been closed.` });
  }, [toast]);

  return {
    state: {
        users,
        allUsers,
        selectedUserId,
        availableUsers
    },
    actions: {
        addUser,
        addAllUsers,
        removeAllUsers,
        removeUser,
        setSelectedUserId
    }
  };
};