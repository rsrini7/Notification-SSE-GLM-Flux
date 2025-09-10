import { useState, useEffect, useCallback, useMemo } from 'react';
import { useToast } from '@/hooks/use-toast';
import { userService } from '../services/api';
import { v4 as uuidv4 } from 'uuid';

// Define the new state structure for a panel
export interface UserPanel {
  panelId: string;
  userId: string;
}

export const useUserPanelManager = () => {

  const [userPanels, setUserPanels] = useState<UserPanel[]>([{ panelId: uuidv4(), userId: 'user-001' }]);
  const [allowDuplicates, setAllowDuplicates] = useState<boolean>(false);

  const [users, setUsers] = useState<string[]>(['user-001']);
  const [allUsers, setAllUsers] = useState<string[]>([]);
  const [selectedUserId, setSelectedUserId] = useState('');
  const { toast } = useToast();

  useEffect(() => {
    const fetchAllUsers = async () => {
      try {
        const userList = await userService.getAllUsers();
        if (Array.isArray(userList)) {
          setAllUsers(userList);
        } else {
          console.error("Failed to fetch a valid user list. API response was not an array.");
          setAllUsers([]); // Ensure it remains a valid array
        }
      } catch (error) {
        toast({
          title: 'Error Fetching Users',
          description: `Could not retrieve user list. The admin service may still be starting.`,
          variant: 'destructive',
        });
        setAllUsers([]);
      }
    };
    fetchAllUsers();
  }, [toast]);

  const availableUsers = useMemo(() => allUsers.filter(u => !users.includes(u)), [allUsers, users]);

  const addUser = useCallback(() => {
    const userToAdd = selectedUserId || (availableUsers.length > 0 ? availableUsers[0] : '');

    if (!userToAdd) return;

    // Check for duplicates only if the checkbox is NOT checked
    const userExists = userPanels.some(panel => panel.userId === userToAdd);
    if (!allowDuplicates && userExists) {
      toast({ title: 'User Exists', description: `A panel for ${userToAdd} is already open.`, variant: 'destructive' });
      return;
    }
    
    setUserPanels(prevPanels => [...prevPanels, { panelId: uuidv4(), userId: userToAdd }]);
    setSelectedUserId('');
    toast({ title: 'User Added', description: `Panel for ${userToAdd} is now active.` });
    
  }, [selectedUserId, userPanels, availableUsers, toast, allowDuplicates]);

  const addAllUsers = useCallback(() => {
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
  }, [allUsers, users, toast]);

  const removeAllUsers = useCallback(() => {
    if (users.length > 0) {
      setUsers([]);
      toast({
        title: 'All Users Removed',
        description: 'All user connection panels have been closed.',
      });
    }
  }, [users.length, toast]);

   // Update removeUser to work with panelId
  const removeUser = useCallback((panelIdToRemove: string) => {
    const userId = userPanels.find(p => p.panelId === panelIdToRemove)?.userId;
    setUserPanels(panels => panels.filter(panel => panel.panelId !== panelIdToRemove));
    if (userId) {
      toast({ title: 'User Removed', description: `Panel for ${userId} has been closed.` });
    }
  }, [toast, userPanels]);

  return {
    state: {
        userPanels,
        allowDuplicates,
        users,
        allUsers,
        selectedUserId,
        availableUsers
    },
    actions: {
        addUser,
        addAllUsers,
        setAllowDuplicates,
        removeAllUsers,
        removeUser,
        setSelectedUserId
    }
  };
};