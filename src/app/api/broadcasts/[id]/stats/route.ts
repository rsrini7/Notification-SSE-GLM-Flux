import { NextRequest, NextResponse } from 'next/server';
import { db } from '@/lib/db';

export async function GET(
  request: NextRequest,
  { params }: { params: { id: string } }
) {
  try {
    const broadcastId = params.id;
    
    // Try to find the broadcast by the provided ID
    // First try as-is (for string IDs), then try to convert to number and find matching broadcast
    let broadcast = await db.broadcast.findUnique({
      where: {
        id: broadcastId
      }
    });

    // If not found, try to find by matching the numeric ID pattern
    if (!broadcast) {
      const allBroadcasts = await db.broadcast.findMany();
      broadcast = allBroadcasts.find(b => {
        const numericId = parseInt(b.id.replace(/[^\d]/g, '')) || 0;
        return numericId.toString() === broadcastId;
      }) || null;
    }

    if (!broadcast) {
      return NextResponse.json(
        { error: 'Broadcast not found' },
        { status: 404 }
      );
    }

    // Calculate stats
    const deliveryRate = broadcast.totalTargeted > 0 
      ? (broadcast.totalDelivered / broadcast.totalTargeted) * 100 
      : 0;
    const readRate = broadcast.totalDelivered > 0 
      ? (broadcast.totalRead / broadcast.totalDelivered) * 100 
      : 0;

    const stats = {
      totalTargeted: broadcast.totalTargeted,
      totalDelivered: broadcast.totalDelivered,
      totalRead: broadcast.totalRead,
      deliveryRate: Math.round(deliveryRate * 100) / 100,
      readRate: Math.round(readRate * 100) / 100
    };

    return NextResponse.json(stats);
  } catch (error) {
    console.error('Error fetching broadcast stats:', error);
    return NextResponse.json(
      { error: 'Failed to fetch broadcast statistics' },
      { status: 500 }
    );
  }
}