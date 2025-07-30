import { NextRequest, NextResponse } from 'next/server';
import { db } from '@/lib/db';

export async function GET(
  request: NextRequest,
  { params }: { params: { id: string } }
) {
  try {
    const broadcastId = params.id;
    
    // Fetch delivery details for this broadcast
    const deliveries = await db.broadcastDelivery.findMany({
      where: {
        broadcastId: broadcastId
      },
      orderBy: {
        deliveredAt: 'desc'
      },
      take: 50 // Limit to last 50 deliveries
    });

    // Transform the data for the frontend
    const transformedDeliveries = deliveries.map(delivery => ({
      id: delivery.id,
      userId: delivery.userId,
      deliveredAt: delivery.deliveredAt.toISOString(),
      readAt: delivery.readAt?.toISOString(),
      status: delivery.status,
      timeToRead: delivery.readAt 
        ? Math.round((delivery.readAt.getTime() - delivery.deliveredAt.getTime()) / 1000) // seconds
        : null
    }));

    return NextResponse.json(transformedDeliveries);
  } catch (error) {
    console.error('Error fetching delivery details:', error);
    return NextResponse.json(
      { error: 'Failed to fetch delivery details' },
      { status: 500 }
    );
  }
}