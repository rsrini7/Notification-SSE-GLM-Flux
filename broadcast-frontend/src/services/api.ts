import axios from 'axios';

// Create an Axios instance for the Admin Service
const adminApi = axios.create({
  baseURL: import.meta.env.VITE_ADMIN_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Create an Axios instance for the User Service
const userApi = axios.create({
  baseURL: import.meta.env.VITE_USER_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});


// Interface definitions
export interface DltMessage {
  id: string;
  originalTopic: string;
  exceptionMessage: string;
  failedAt: string;
  originalMessagePayload: string;
}
export interface BroadcastMessage {
  id: string;
  senderId: string;
  senderName: string;
  content: string;
  targetType: string;
  targetIds: string[];
  priority: string;
  category: string;
  createdAt: string;
  status: string;
  totalTargeted: number;
  totalDelivered: number;
  totalRead: number;
  scheduledAt: string;
  expiresAt?: string;
  isImmediate: boolean;
}
export interface BroadcastStats {
  totalTargeted: number;
  totalDelivered: number;
  totalRead: number;
  deliveryRate: number;
  readRate: number;
}
export interface UserBroadcastMessage {
  userMessageId: number | null;
  broadcastId: number;
  userId: string;
  deliveryStatus: string;
  readStatus: string;
  deliveredAt?: string;
  readAt?: string;
  createdAt: string;
  senderName: string;
  content: string;
  priority: string;
  category: string;
  broadcastCreatedAt: string;
  expiresAt?: string;
}
export interface BroadcastRequest {
  senderId: string;
  senderName: string;
  content: string;
  targetType: string;
  targetIds: string[];
  priority: string;
  category: string;
  scheduledAt?: string;
  expiresAt?: string;
  isImmediate: boolean;
  fireAndForget?: boolean;
}
export interface RedriveAllResult {
  totalMessages: number;
  successCount: number;
  failureCount: number;
  failures: { dltMessageId: string; reason: string }[];
}


// Services using the adminApi instance
export const broadcastService = {
  getBroadcasts: async (filter = 'all'): Promise<BroadcastMessage[]> => {
    const response = await adminApi.get(`/broadcasts?filter=${filter}`);
    return response.data;
  },
  createBroadcast: async (broadcast: BroadcastRequest): Promise<BroadcastMessage> => {
    const response = await adminApi.post('/broadcasts', broadcast);
    return response.data;
  },
  cancelBroadcast: async (id: string): Promise<void> => {
    await adminApi.delete(`/broadcasts/${id}`);
  },
  getBroadcastStats: async (id: string): Promise<BroadcastStats> => {
    const response = await adminApi.get(`/broadcasts/${id}/stats`);
    return response.data;
  },
  getBroadcastDeliveries: async (id: string): Promise<any[]> => {
    const response = await adminApi.get(`/broadcasts/${id}/deliveries`);
    return response.data;
  },
};

export const dltService = {
  getDltMessages: async (): Promise<DltMessage[]> => {
    const response = await adminApi.get('/dlt/messages');
    return response.data;
  },
  redriveDltMessage: async (id: string): Promise<void> => {
    await adminApi.post(`/dlt/redrive/${id}`);
  },
  redriveAllDltMessages: async (): Promise<RedriveAllResult> => {
    const response = await adminApi.post('/dlt/redrive-all');
    return response.data;
  },
  purgeDltMessage: async (id: string): Promise<void> => {
    await adminApi.delete(`/dlt/purge/${id}`);
  },
  purgeAllDltMessages: async (): Promise<void> => {
    await adminApi.delete('/dlt/purge-all');
  },
};

// Services using the userApi instance
export const userService = {
  getUserMessages: async (userId: string): Promise<UserBroadcastMessage[]> => {
    const response = await userApi.get(`/messages?userId=${userId}`);
    return response.data;
  },
  getAllUsers: async (): Promise<string[]> => {
    const response = await adminApi.get('/broadcasts/users/all-ids');
    return response.data;
  },
};