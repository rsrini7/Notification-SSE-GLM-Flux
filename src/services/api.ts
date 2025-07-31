import axios from 'axios';

const API_BASE_URL = process.env.NODE_ENV === 'production' 
  ? 'https://your-java-backend.com' 
  : 'http://localhost:8081';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor for logging
/* api.interceptors.request.use(
  (config) => {
    console.log(`API Request: ${config.method?.toUpperCase()} ${config.url}`, config.data);
    return config;
  },
  (error) => {
    console.error('API Request Error:', error);
    return Promise.reject(error);
  }
);

// Response interceptor for error handling
api.interceptors.response.use(
  (response) => {
    console.log(`API Response: ${response.config.method?.toUpperCase()} ${response.config.url}`, response.data);
    return response;
  },
  (error) => {
    console.error('API Response Error:', error.response?.data || error.message);
    return Promise.reject(error);
  }
);*/

// Interfaces
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
  // **NEW:** Added expiresAt to the user message interface
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

// Services (implementations remain the same)
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
};

export default api;
