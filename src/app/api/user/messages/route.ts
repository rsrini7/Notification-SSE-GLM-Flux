import { NextRequest, NextResponse } from 'next/server';
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

    const now = new Date();
    
    // Fetch broadcasts from database with scheduling logic
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

    // Transform broadcasts to messages format and track deliveries
    const transformedMessages = activeBroadcasts.map(broadcast => ({
      id: `msg-${broadcast.id}-${Date.now()}`,
      broadcastId: broadcast.id,
      userId: userId,
      deliveryStatus: 'DELIVERED',
      readStatus: 'UNREAD',
      deliveredAt: now.toISOString(),
      createdAt: broadcast.createdAt.toISOString(), // Use the original broadcast creation time
      senderName: broadcast.senderName,
      content: broadcast.content,
      priority: broadcast.priority,
      category: broadcast.category,
      broadcastCreatedAt: broadcast.createdAt.toISOString(),
      timestamp: Math.max(new Date(broadcast.createdAt).getTime(), lastTimestamp + 1)
    }));

    // Track deliveries in the database
    for (const message of transformedMessages) {
      try {
        // Check if this delivery has already been tracked for this user
        const existingDelivery = await db.broadcastDelivery.findFirst({
          where: {
            broadcastId: message.broadcastId,
            userId: userId
          }
        });

        if (!existingDelivery) {
          // Create delivery record
          await db.broadcastDelivery.create({
            data: {
              broadcastId: message.broadcastId,
              userId: userId,
              deliveredAt: now,
              readAt: null,
              status: 'DELIVERED'
            }
          });

          // Update broadcast statistics
          await db.broadcast.update({
            where: { id: message.broadcastId },
            data: {
              totalDelivered: {
                increment: 1
              }
            }
          });

          console.log(`Tracked delivery for broadcast ${message.broadcastId} to user ${userId}`);
        }
      } catch (error) {
        console.error(`Error tracking delivery for broadcast ${message.broadcastId}:`, error);
      }
    }

    // Filter by last timestamp if provided
    const filteredMessages = lastTimestamp > 0 
      ? transformedMessages.filter(msg => msg.timestamp > lastTimestamp)
      : transformedMessages;

    // Sort messages by creation time (newest first)
    const sortedMessages = filteredMessages.sort((a, b) => 
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );

    console.log(`Getting messages for user ${userId}: found ${sortedMessages.length} messages`);

    return NextResponse.json(sortedMessages);
  } catch (error) {
    console.error('Error fetching user messages:', error);
    return NextResponse.json(
      { error: 'Failed to fetch user messages' },
      { status: 500 }
    );
  }
}