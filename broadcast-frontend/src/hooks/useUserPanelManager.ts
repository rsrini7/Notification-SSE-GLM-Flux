import { useState, useEffect, useCallback, useMemo } from 'react';
import { useToast } from '@/hooks/use-toast';
import { userService } from '../services/api';
import { v4 as uuidv4 } from 'uuid';

// Define the state structure for a panel
export interface UserPanel {
  panelId: string;
  userId: string;
}

export const useUserPanelManager = () => {
  const [userPanels, setUserPanels] = useState<UserPanel[]>([{ panelId: uuidv4(), userId: 'user-001' }]);
  const [allowDuplicates, setAllowDuplicates] = useState<boolean>(false);
  const [allUsers, setAllUsers] = useState<string[]>([]);
  const [selectedUserId, setSelectedUserId] = useState('');
  const { toast } = useToast();

  useEffect(() => {
    // This effect remains the same
    const fetchAllUsers = async () => {
      try {
        const userList = await userService.getAllUsers();
        if (Array.isArray(userList)) {
          setAllUsers(userList);
        } else {
          setAllUsers([]);
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

  // availableUsers is now derived from userPanels
  const availableUsers = useMemo(() => {
      // If duplicates are allowed, the available list is always the full list of users.
      if (allowDuplicates) {
        return allUsers;
      }
      // Otherwise (default behavior), filter out users who are already in a panel.
      const currentPanelUserIds = userPanels.map(panel => panel.userId);
      return allUsers.filter(u => !currentPanelUserIds.includes(u));
  }, [allUsers, userPanels, allowDuplicates]);

  // CORRECTED: addUser logic is mostly the same, but was already correct
  const addUser = useCallback(() => {
    const userToAdd = selectedUserId || (availableUsers.length > 0 ? availableUsers[0] : '');
    if (!userToAdd) return;

    const userExists = userPanels.some(panel => panel.userId === userToAdd);
    if (!allowDuplicates && userExists) {
      toast({ title: 'User Exists', description: `A panel for ${userToAdd} is already open.`, variant: 'destructive' });
      return;
    }
    
    setUserPanels(prevPanels => [...prevPanels, { panelId: uuidv4(), userId: userToAdd }]);
    setSelectedUserId('');
    toast({ title: 'User Added', description: `Panel for ${userToAdd} is now active.` });
    
  }, [selectedUserId, userPanels, availableUsers, toast, allowDuplicates]);

  // CORRECTED: addAllUsers now updates the correct state
  const addAllUsers = useCallback(() => {
    const usersToAdd = allUsers.filter(u => !userPanels.some(p => p.userId === u));
    if (usersToAdd.length > 0) {
      const newPanels = usersToAdd.map(userId => ({ panelId: uuidv4(), userId }));
      setUserPanels(prevPanels => [...prevPanels, ...newPanels]);
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
  }, [allUsers, userPanels, toast]);

  // CORRECTED: removeAllUsers now updates the correct state
  const removeAllUsers = useCallback(() => {
    if (userPanels.length > 0) {
      setUserPanels([]);
      toast({
        title: 'All Users Removed',
        description: 'All user connection panels have been closed.',
      });
    }
  }, [userPanels.length, toast]);

  // This function was already correct
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