// In-memory storage for broadcast messages and user sessions
// Using global variables to persist across module reloads
declare global {
  var broadcastMessages: Map<string, any[]>;
  var userSessions: Map<string, string>;
}

// Initialize global variables if they don't exist
if (!global.broadcastMessages) {
  global.broadcastMessages = new Map();
}

if (!global.userSessions) {
  global.userSessions = new Map();
}

// Function to deliver a broadcast to users
export async function deliverBroadcastToUsers(broadcast: any) {
  try {
    console.log('Delivering broadcast to users:', broadcast.id);
    
    // Create a message object for the broadcast
    const message = {
      id: `msg-${broadcast.id}`, // Use consistent ID based on broadcast ID
      broadcastId: broadcast.id,
      senderId: broadcast.senderId,
      senderName: broadcast.senderName,
      content: broadcast.content,
      priority: broadcast.priority,
      category: broadcast.category,
      createdAt: broadcast.createdAt,
      startTime: broadcast.startTime,
      endTime: broadcast.endTime,
      timestamp: new Date(broadcast.createdAt).getTime()
    };

    // Store the message for polling
    // For ALL broadcasts, store in a global list
    if (broadcast.targetType === 'ALL') {
      if (!global.broadcastMessages.has('ALL')) {
        global.broadcastMessages.set('ALL', []);
      }
      global.broadcastMessages.get('ALL')!.push(message);
      
      // Also store for individual users (simulate delivery to all users)
      // In a real system, this would be more sophisticated
      const sampleUsers = ['user-001', 'user-002', 'user-003'];
      sampleUsers.forEach(userId => {
        if (!global.broadcastMessages.has(userId)) {
          global.broadcastMessages.set(userId, []);
        }
        global.broadcastMessages.get(userId)!.push(message);
      });
    } else {
      // For targeted broadcasts, store for specific users
      const targetUsers = broadcast.targetIds || [];
      targetUsers.forEach((userId: string) => {
        if (!global.broadcastMessages.has(userId)) {
          global.broadcastMessages.set(userId, []);
        }
        global.broadcastMessages.get(userId)!.push(message);
      });
    }

    console.log(`Broadcast ${broadcast.id} delivered to users`);
    console.log(`Total messages in ALL queue: ${global.broadcastMessages.get('ALL')?.length || 0}`);
    console.log(`Total messages in user-001 queue: ${global.broadcastMessages.get('user-001')?.length || 0}`);
  } catch (error) {
    console.error('Error delivering broadcast to users:', error);
    throw error;
  }
}

// Function to clean up expired messages from SSE storage
export function cleanupExpiredMessages() {
  const now = new Date();
  let totalCleaned = 0;
  
  // Clean up ALL messages
  const allMessages = global.broadcastMessages.get('ALL') || [];
  const filteredAllMessages = allMessages.filter(msg => {
    if (msg.endTime) {
      const endTime = new Date(msg.endTime);
      return now <= endTime;
    }
    return true; // Keep messages without endTime
  });
  
  totalCleaned += allMessages.length - filteredAllMessages.length;
  global.broadcastMessages.set('ALL', filteredAllMessages);
  
  // Clean up user-specific messages
  for (const [userId, messages] of global.broadcastMessages.entries()) {
    if (userId === 'ALL') continue; // Skip ALL, already handled
    
    const filteredMessages = messages.filter(msg => {
      if (msg.endTime) {
        const endTime = new Date(msg.endTime);
        return now <= endTime;
      }
      return true; // Keep messages without endTime
    });
    
    totalCleaned += messages.length - filteredMessages.length;
    global.broadcastMessages.set(userId, filteredMessages);
  }
  
  // Also clean up old messages (older than 1 hour) to prevent memory leaks
  const oneHourAgo = new Date(now.getTime() - 60 * 60 * 1000);
  for (const [userId, messages] of global.broadcastMessages.entries()) {
    const recentMessages = messages.filter(msg => {
      const msgTime = new Date(msg.timestamp);
      return msgTime > oneHourAgo;
    });
    
    const oldCount = messages.length;
    const newCount = recentMessages.length;
    if (oldCount !== newCount) {
      totalCleaned += oldCount - newCount;
      global.broadcastMessages.set(userId, recentMessages);
    }
  }
  
  if (totalCleaned > 0) {
    console.log(`Cleaned up ${totalCleaned} expired messages from SSE storage`);
  }
  
  return totalCleaned;
}

// Function to get messages for a user (for polling)
export function getUserMessages(userId: string, lastTimestamp: number = 0) {
  const userMessages = global.broadcastMessages.get(userId) || [];
  const allMessages = global.broadcastMessages.get('ALL') || [];
  
  // Combine user-specific messages and ALL messages
  const allUserMessages = [...userMessages, ...allMessages];
  
  // Create a map to remove duplicates based on broadcast ID
  const messageMap = new Map();
  allUserMessages.forEach(msg => {
    const key = msg.broadcastId || msg.id;
    if (!messageMap.has(key) || msg.timestamp > messageMap.get(key).timestamp) {
      messageMap.set(key, msg);
    }
  });
  
  // Convert back to array and filter by timestamp
  const uniqueMessages = Array.from(messageMap.values());
  const newMessages = uniqueMessages.filter(msg => msg.timestamp > lastTimestamp);
  
  // Sort messages by timestamp (newest first)
  const sortedMessages = newMessages.sort((a, b) => b.timestamp - a.timestamp);
  
  console.log(`Getting messages for user ${userId}: found ${newMessages.length} new messages since ${lastTimestamp}`);
  
  return sortedMessages;
}

// Function to mark a message as read
export function markMessageAsRead(userId: string, messageId: string) {
  // In a real implementation, this would update the message status in the database
  console.log(`Message ${messageId} marked as read by user ${userId}`);
  return true;
}

// Function to register a user session
export function registerUserSession(userId: string, sessionId: string) {
  global.userSessions.set(sessionId, userId);
  console.log(`User session registered: ${userId} - ${sessionId}`);
}

// Function to unregister a user session
export function unregisterUserSession(sessionId: string) {
  const userId = global.userSessions.get(sessionId);
  if (userId) {
    global.userSessions.delete(sessionId);
    console.log(`User session unregistered: ${userId} - ${sessionId}`);
  }
}

// Function to get active user sessions
export function getActiveUserSessions() {
  return Array.from(global.userSessions.entries());
}

// Function to get debug information
export function getDebugInfo() {
  return {
    broadcastMessagesSize: global.broadcastMessages.size,
    userSessionsSize: global.userSessions.size,
    allMessagesCount: global.broadcastMessages.get('ALL')?.length || 0,
    user001MessagesCount: global.broadcastMessages.get('user-001')?.length || 0
  };
}