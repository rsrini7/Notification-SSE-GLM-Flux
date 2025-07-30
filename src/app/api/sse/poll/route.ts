import { NextRequest, NextResponse } from 'next/server';
import { getUserMessages, registerUserSession, unregisterUserSession, cleanupExpiredMessages } from '@/lib/sse';
import { db } from '@/lib/db';

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const userId = searchParams.get('userId');
    const lastTimestamp = parseInt(searchParams.get('lastTimestamp') || '0');

    if (!userId) {
      return NextResponse.json(
        { error: 'Missing userId' },
        { status: 400 }
      );
    }

    // Clean up expired messages from SSE storage
    cleanupExpiredMessages();
    
    // Get messages from the SSE system
    const sseMessages = getUserMessages(userId, lastTimestamp);

    // Also get fresh broadcasts from database to respect end times
    const now = new Date();
    const broadcasts = await db.broadcast.findMany({
      orderBy: {
        createdAt: 'desc'
      }
    });

    // Filter broadcasts based on scheduling logic and user targeting
    const activeBroadcasts = broadcasts.filter(broadcast => {
      const startTime = new Date(broadcast.startTime);
      const endTime = broadcast.endTime ? new Date(broadcast.endTime) : null;
      
      // Check if broadcast is currently active
      const isAfterStart = now >= startTime;
      const isBeforeEnd = !endTime || now <= endTime;
      
      // Check if broadcast is targeted to this user
      const isTargetedToUser = broadcast.targetType === 'ALL' || 
        JSON.parse(broadcast.targetIds || '[]').includes(userId);
      
      return isAfterStart && isBeforeEnd && isTargetedToUser;
    });

    // Transform active broadcasts to messages format
    const dbMessages = activeBroadcasts.map(broadcast => ({
      id: `msg-${broadcast.id}`, // Use consistent ID based on broadcast ID
      broadcastId: broadcast.id,
      userId: userId,
      deliveryStatus: 'DELIVERED',
      readStatus: 'UNREAD',
      deliveredAt: now.toISOString(),
      createdAt: broadcast.createdAt.toISOString(),
      senderName: broadcast.senderName,
      content: broadcast.content,
      priority: broadcast.priority,
      category: broadcast.category,
      broadcastCreatedAt: broadcast.createdAt.toISOString(),
      timestamp: new Date(broadcast.createdAt).getTime()
    }));

    // Combine SSE messages and DB messages, but filter out expired ones
    const allMessages = [...sseMessages, ...dbMessages];
    
    // Filter out messages from expired broadcasts
    const activeBroadcastIds = new Set(activeBroadcasts.map(b => b.id));
    const filteredMessages = allMessages.filter(msg => {
      const now = new Date();
      
      // If it's a database message, it's already filtered
      if (msg.id.startsWith('msg-') && msg.broadcastId) {
        return true;
      }
      
      // For SSE messages, check if the broadcast is still active
      if (msg.endTime) {
        const endTime = new Date(msg.endTime);
        if (now > endTime) {
          return false; // Broadcast has expired
        }
      }
      
      // Also check start time for completeness
      if (msg.startTime) {
        const startTime = new Date(msg.startTime);
        if (now < startTime) {
          return false; // Broadcast hasn't started yet
        }
      }
      
      return true; // Message is still active
    });

    // Remove duplicates based on broadcast ID to ensure each broadcast appears only once
    const messageMap = new Map();
    filteredMessages.forEach(msg => {
      const key = msg.broadcastId || msg.id;
      if (!messageMap.has(key) || msg.timestamp > messageMap.get(key).timestamp) {
        messageMap.set(key, msg);
      }
    });

    const uniqueMessages = Array.from(messageMap.values());
    const finalMessages = uniqueMessages.filter(msg => msg.timestamp > lastTimestamp);

    // Sort messages by timestamp (newest first)
    const sortedMessages = finalMessages.sort((a, b) => b.timestamp - a.timestamp);

    console.log(`Getting messages for user ${userId}: found ${sortedMessages.length} active messages since ${lastTimestamp}`);

    return NextResponse.json({
      messages: sortedMessages,
      timestamp: Date.now().toString()
    });
  } catch (error) {
    console.error('Error in SSE poll:', error);
    return NextResponse.json(
      { error: 'Failed to fetch messages' },
      { status: 500 }
    );
  }
}

export async function POST(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const userId = searchParams.get('userId');

    if (!userId) {
      return NextResponse.json(
        { error: 'Missing userId' },
        { status: 400 }
      );
    }

    const body = await request.json();
    
    // Handle different types of POST requests
    if (body.type === 'register') {
      const sessionId = body.sessionId || `session-${Date.now()}`;
      registerUserSession(userId, sessionId);
      return NextResponse.json({
        success: true,
        sessionId: sessionId,
        message: 'User session registered'
      });
    } else if (body.type === 'unregister') {
      const sessionId = body.sessionId;
      if (sessionId) {
        unregisterUserSession(sessionId);
      }
      return NextResponse.json({
        success: true,
        message: 'User session unregistered'
      });
    } else {
      // Handle regular message posting
      return NextResponse.json({
        success: true,
        message: 'Message type not supported',
        timestamp: Date.now().toString()
      });
    }
  } catch (error) {
    console.error('Error in SSE poll POST:', error);
    return NextResponse.json(
      { error: 'Failed to process request' },
      { status: 500 }
    );
  }
}