import { NextRequest, NextResponse } from 'next/server';
import { db } from '@/lib/db';

export async function DELETE(
  request: NextRequest,
  { params }: { params: { id: string } }
) {
  try {
    const broadcastId = params.id;
    
    // Try to find the broadcast by the provided ID
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

    // Update the broadcast status to CANCELLED
    const updatedBroadcast = await db.broadcast.update({
      where: {
        id: broadcast.id
      },
      data: {
        status: 'CANCELLED'
      }
    });
    
    return NextResponse.json({ 
      message: 'Broadcast cancelled successfully',
      broadcastId: broadcastId,
      status: updatedBroadcast.status
    });
  } catch (error) {
    console.error('Error cancelling broadcast:', error);
    return NextResponse.json(
      { error: 'Failed to cancel broadcast' },
      { status: 500 }
    );
  }
}