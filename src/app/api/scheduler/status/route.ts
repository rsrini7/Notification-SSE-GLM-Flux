import { NextRequest, NextResponse } from 'next/server';
import { broadcastScheduler } from '@/lib/broadcast-scheduler';

export async function GET(request: NextRequest) {
  try {
    const status = broadcastScheduler.getStatus();
    
    return NextResponse.json({
      scheduler: {
        isRunning: status.isRunning,
        nextCheck: status.nextCheck?.toISOString() || null
      },
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    console.error('Error getting scheduler status:', error);
    return NextResponse.json(
      { error: 'Failed to get scheduler status' },
      { status: 500 }
    );
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { action } = body;

    if (action === 'start') {
      broadcastScheduler.start();
      return NextResponse.json({
        success: true,
        message: 'Broadcast scheduler started',
        timestamp: new Date().toISOString()
      });
    } else if (action === 'stop') {
      broadcastScheduler.stop();
      return NextResponse.json({
        success: true,
        message: 'Broadcast scheduler stopped',
        timestamp: new Date().toISOString()
      });
    } else if (action === 'check') {
      // Manually trigger a check
      await (broadcastScheduler as any).checkAndDeliverScheduledBroadcasts();
      return NextResponse.json({
        success: true,
        message: 'Manual scheduler check triggered',
        timestamp: new Date().toISOString()
      });
    } else {
      return NextResponse.json(
        { error: 'Invalid action' },
        { status: 400 }
      );
    }
  } catch (error) {
    console.error('Error performing scheduler action:', error);
    return NextResponse.json(
      { error: 'Failed to perform scheduler action' },
      { status: 500 }
    );
  }
}