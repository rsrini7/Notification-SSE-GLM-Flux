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

    // In a real implementation, this would clean up the SSE connection
    // For now, we'll just return success
    
    return NextResponse.json({ 
      message: 'SSE connection disconnected',
      userId: userId,
      sessionId: sessionId,
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    console.error('Error disconnecting SSE:', error);
    return NextResponse.json(
      { error: 'Failed to disconnect SSE' },
      { status: 500 }
    );
  }
}