import { NextRequest, NextResponse } from 'next/server';
import { db } from '@/lib/db';

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const filter = searchParams.get('filter'); // 'active', 'all', 'scheduled'
    const now = new Date();
    
    const broadcasts = await db.broadcast.findMany({
      orderBy: {
        createdAt: 'desc'
      }
    });
    
    let filteredBroadcasts = broadcasts;
    
    // Filter broadcasts based on the filter parameter
    if (filter === 'active') {
      filteredBroadcasts = broadcasts.filter(broadcast => {
        const startTime = new Date(broadcast.startTime);
        const endTime = broadcast.endTime ? new Date(broadcast.endTime) : null;
        
        // Broadcast is active if current time is after start time
        // and before end time (if end time is specified)
        const isAfterStart = now >= startTime;
        const isBeforeEnd = !endTime || now <= endTime;
        
        return isAfterStart && isBeforeEnd;
      });
    } else if (filter === 'scheduled') {
      filteredBroadcasts = broadcasts.filter(broadcast => {
        const startTime = new Date(broadcast.startTime);
        
        // Broadcast is scheduled if start time is in the future
        const isFuture = now < startTime;
        
        return isFuture;
      });
    }
    // If filter is 'all' or not specified, show all broadcasts
    
    // Transform the data to match the expected format
    const transformedBroadcasts = filteredBroadcasts.map(broadcast => ({
      ...broadcast,
      targetIds: JSON.parse(broadcast.targetIds || '[]'),
      id: parseInt(broadcast.id.replace(/[^\d]/g, '')) || Math.floor(Math.random() * 1000000), // Convert string ID to number for frontend compatibility
      startTime: broadcast.startTime.toISOString(),
      endTime: broadcast.endTime ? broadcast.endTime.toISOString() : undefined
    }));
    
    return NextResponse.json(transformedBroadcasts);
  } catch (error) {
    console.error('Error fetching broadcasts:', error);
    return NextResponse.json(
      { error: 'Failed to fetch broadcasts' },
      { status: 500 }
    );
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    
    const newBroadcast = await db.broadcast.create({
      data: {
        senderId: body.senderId || 'admin-001',
        senderName: body.senderName || 'System Administrator',
        content: body.content,
        targetType: body.targetType || 'ALL',
        targetIds: JSON.stringify(body.targetIds || []),
        priority: body.priority || 'NORMAL',
        category: body.category || 'GENERAL',
        totalTargeted: body.targetType === 'ALL' ? 400000 : (body.targetIds || []).length,
        totalDelivered: 0,
        totalRead: 0,
        startTime: body.startTime ? new Date(body.startTime) : new Date(),
        endTime: body.endTime ? new Date(body.endTime) : null,
        isImmediate: body.isImmediate || true
      }
    });

    // Transform the response to match expected format
    const transformedBroadcast = {
      ...newBroadcast,
      targetIds: JSON.parse(newBroadcast.targetIds || '[]'),
      id: parseInt(newBroadcast.id.replace(/[^\d]/g, '')) || Math.floor(Math.random() * 1000000),
      startTime: newBroadcast.startTime.toISOString(),
      endTime: newBroadcast.endTime ? newBroadcast.endTime.toISOString() : undefined
    };

    // Only deliver broadcast if it's immediate AND the scheduled time has passed
    const now = new Date();
    const startTime = new Date(newBroadcast.startTime);
    
    if (newBroadcast.isImmediate && now >= startTime) {
      // Deliver the broadcast to users via the polling system
      try {
        const { deliverBroadcastToUsers } = await import('@/lib/sse');
        await deliverBroadcastToUsers({
          ...transformedBroadcast,
          id: newBroadcast.id, // Use the original database ID for delivery
          timestamp: Date.now()
        });
        console.log('Broadcast delivered to users successfully');
      } catch (deliveryError) {
        console.error('Failed to deliver broadcast to users:', deliveryError);
        // Don't fail the broadcast creation if delivery fails
      }
    } else if (!newBroadcast.isImmediate) {
      console.log('Broadcast scheduled for future delivery:', startTime);
      console.log('This is a scheduled broadcast and will not be delivered until:', startTime);
      
      // Ensure the scheduler is running for scheduled broadcasts
      try {
        const { startBroadcastScheduler } = await import('@/lib/broadcast-scheduler');
        startBroadcastScheduler();
        console.log('Broadcast scheduler ensured to be running');
      } catch (schedulerError) {
        console.error('Failed to ensure scheduler is running:', schedulerError);
      }
    } else {
      console.log('Immediate broadcast created but scheduled for future delivery:', startTime);
    }
    
    return NextResponse.json(transformedBroadcast, { status: 201 });
  } catch (error) {
    console.error('Error creating broadcast:', error);
    return NextResponse.json(
      { error: 'Failed to create broadcast' },
      { status: 500 }
    );
  }
}