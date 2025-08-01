import axios from 'axios';

// START OF FIX: Make the base URL relative so the Vite proxy can handle all requests.
const API_BASE_URL = ''; 
// END OF FIX

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

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
  id: number;
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
}

export const broadcastService = {
  getBroadcasts: async (filter = 'all'): Promise<BroadcastMessage[]> => {
    const response = await api.get(`/api/broadcasts?filter=${filter}`);
    return response.data;
  },
  createBroadcast: async (broadcast: BroadcastRequest): Promise<BroadcastMessage> => {
    const response = await api.post('/api/broadcasts', broadcast);
    return response.data;
  },
  getBroadcast: async (id: string): Promise<BroadcastMessage> => {
    const response = await api.get(`/api/broadcasts/${id}`);
    return response.data;
  },
  cancelBroadcast: async (id: string): Promise<void> => {
    await api.delete(`/api/broadcasts/${id}`);
  },
  getBroadcastStats: async (id: string): Promise<BroadcastStats> => {
    const response = await api.get(`/api/broadcasts/${id}/stats`);
    return response.data;
  },
  getBroadcastDeliveries: async (id: string): Promise<any[]> => {
    const response = await api.get(`/api/broadcasts/${id}/deliveries`);
    return response.data;
  },
};
export const userService = {
  getUserMessages: async (userId: string): Promise<UserBroadcastMessage[]> => {
    const response = await api.get(`/api/user/messages?userId=${userId}`);
    return response.data;
  },
  markMessageAsRead: async (userId: string, messageId: string): Promise<void> => {
    await api.post(`/api/sse/read?userId=${userId}&messageId=${messageId}`);
  },
  getAllUsers: async (): Promise<string[]> => {
    const response = await api.get('/api/user/all');
    return response.data;
  },
};
export const dltService = {
  getDltMessages: async (): Promise<DltMessage[]> => {
    const response = await api.get('/api/dlt/messages');
    return response.data;
  },
  redriveDltMessage: async (id: string): Promise<void> => {
    await api.post(`/api/dlt/redrive/${id}`);
  },
  // This is the "soft delete" from the application's perspective.
  deleteDltMessage: async (id: string): Promise<void> => {
    await api.delete(`/api/dlt/delete/${id}`);
  },
  // NEW: This is the "hard delete" that also purges from Kafka.
  purgeDltMessage: async (id: string): Promise<void> => {
    await api.delete(`/api/dlt/purge/${id}`);
  },
};

export default api;