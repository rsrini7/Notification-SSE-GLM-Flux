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
api.interceptors.request.use(
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
);

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
  // **FIX:** Changed `startTime` to `scheduledAt` and `endTime` to `expiresAt`
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
}

export interface SseMessage {
  id: string;
  senderId: string;
  senderName: string;
  content: string;
  priority: string;
  category: string;
  createdAt: string;
}

export interface BroadcastRequest {
  senderId: string;
  senderName: string;
  content: string;
  targetType: string;
  targetIds: string[];
  priority: string;
  category: string;
  // **FIX:** Changed `startTime` to `scheduledAt` and `endTime` to `expiresAt`
  scheduledAt?: string;
  expiresAt?: string;
  isImmediate: boolean;
}

export const broadcastService = {
  // Get all broadcasts
  getBroadcasts: async (filter = 'all'): Promise<BroadcastMessage[]> => {
    const response = await api.get(`/api/broadcasts?filter=${filter}`);
    return response.data;
  },

  // Create a new broadcast
  createBroadcast: async (broadcast: BroadcastRequest): Promise<BroadcastMessage> => {
    const response = await api.post('/api/broadcasts', broadcast);
    return response.data;
  },

  // Get broadcast by ID
  getBroadcast: async (id: string): Promise<BroadcastMessage> => {
    const response = await api.get(`/api/broadcasts/${id}`);
    return response.data;
  },

  // Cancel a broadcast
  cancelBroadcast: async (id: string): Promise<void> => {
    await api.delete(`/api/broadcasts/${id}`);
  },

  // Get broadcast statistics
  getBroadcastStats: async (id: string): Promise<BroadcastStats> => {
    const response = await api.get(`/api/broadcasts/${id}/stats`);
    return response.data;
  },

  // Get broadcast delivery details
  getBroadcastDeliveries: async (id: string): Promise<any[]> => {
    const response = await api.get(`/api/broadcasts/${id}/deliveries`);
    return response.data;
  },
};

export const userService = {
  // Get user messages
  getUserMessages: async (userId: string): Promise<UserBroadcastMessage[]> => {
    const response = await api.get(`/api/user/messages?userId=${userId}`);
    return response.data;
  },

  // Mark message as read
  markMessageAsRead: async (userId: string, messageId: string): Promise<void> => {
    await api.post(`/api/sse/read?userId=${userId}&messageId=${messageId}`);
  },

  // Connect to SSE
  connectSSE: async (userId: string, sessionId: string): Promise<void> => {
    await api.post(`/api/sse/connect?userId=${userId}&sessionId=${sessionId}`);
  },

  // Disconnect from SSE
  disconnectSSE: async (userId: string, sessionId: string): Promise<void> => {
    await api.post(`/api/sse/disconnect?userId=${userId}&sessionId=${sessionId}`);
  },

  // Send heartbeat
  sendHeartbeat: async (userId: string, sessionId: string): Promise<void> => {
    await api.post(`/api/sse/heartbeat?userId=${userId}&sessionId=${sessionId}`);
  },
};

export const healthService = {
  // Check health
  checkHealth: async (): Promise<any> => {
    const response = await api.get('/api/health');
    return response.data;
  },

  // Get scheduler status
  getSchedulerStatus: async (): Promise<any> => {
    const response = await api.get('/api/scheduler/status');
    return response.data;
  },
};

export default api;
