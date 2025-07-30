import { NextRequest, NextResponse } from 'next/server';

export async function POST(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const userId = searchParams.get('userId');
    const sessionId = searchParams.get('sessionId');

    if (!userId || !sessionId) {
      return NextResponse.json(
        { error: 'Missing userId or sessionId' },
        { status: 400 }
      );
    }

    // In a real implementation, this would update the last heartbeat timestamp
    // For now, we'll just return success
    
    return NextResponse.json({ 
      message: 'Heartbeat received',
      userId: userId,
      sessionId: sessionId,
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    console.error('Error processing heartbeat:', error);
    return NextResponse.json(
      { error: 'Failed to process heartbeat' },
      { status: 500 }
    );
  }
}