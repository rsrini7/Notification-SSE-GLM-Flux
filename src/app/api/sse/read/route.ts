import { NextRequest, NextResponse } from 'next/server';
import { db } from '@/lib/db';

export async function POST(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const userId = searchParams.get('userId');
    const messageId = searchParams.get('messageId');

    if (!userId || !messageId) {
      return NextResponse.json(
        { error: 'Missing userId or messageId' },
        { status: 400 }
      );
    }

    // Extract broadcastId from messageId (format: msg-broadcastId-timestamp)
    const broadcastId = messageId.split('-')[1];
    
    if (!broadcastId) {
      return NextResponse.json(
        { error: 'Invalid messageId format' },
        { status: 400 }
      );
    }

    // Find the delivery record
    const delivery = await db.broadcastDelivery.findFirst({
      where: {
        broadcastId: broadcastId,
        userId: userId
      }
    });

    if (delivery) {
      // Update the delivery record to mark as read
      const now = new Date();
      await db.broadcastDelivery.update({
        where: { id: delivery.id },
        data: {
          readAt: now,
          status: 'READ'
        }
      });

      // Update broadcast statistics
      await db.broadcast.update({
        where: { id: broadcastId },
        data: {
          totalRead: {
            increment: 1
          }
        }
      });

      console.log(`Marked message ${messageId} as read for user ${userId}`);
    } else {
      // Create a new delivery record if it doesn't exist
      const now = new Date();
      await db.broadcastDelivery.create({
        data: {
          broadcastId: broadcastId,
          userId: userId,
          deliveredAt: now,
          readAt: now,
          status: 'READ'
        }
      });

      // Update broadcast statistics
      await db.broadcast.update({
        where: { id: broadcastId },
        data: {
          totalDelivered: {
            increment: 1
          },
          totalRead: {
            increment: 1
          }
        }
      });

      console.log(`Created delivery record and marked message ${messageId} as read for user ${userId}`);
    }
    
    return NextResponse.json({ 
      message: 'Message marked as read',
      userId: userId,
      messageId: messageId,
      broadcastId: broadcastId,
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    console.error('Error marking message as read:', error);
    return NextResponse.json(
      { error: 'Failed to mark message as read' },
      { status: 500 }
    );
  }
}