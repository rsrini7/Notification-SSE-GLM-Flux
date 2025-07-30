import { NextRequest, NextResponse } from 'next/server';

export async function GET(request: NextRequest) {
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

    console.log(`SSE connection requested for user: ${userId}, session: ${sessionId}`);

    // Create a readable stream for SSE
    const stream = new ReadableStream({
      start(controller) {
        console.log(`SSE connection started for session: ${sessionId}`);
        
        // Send initial connection event
        const connectData = {
          type: 'CONNECTED',
          timestamp: new Date().toISOString(),
          message: 'SSE connection established'
        };
        
        const connectEvent = `data: ${JSON.stringify(connectData)}\n\n`;
        controller.enqueue(new TextEncoder().encode(connectEvent));
        
        // Send a test message after 1 second
        setTimeout(() => {
          const testData = {
            type: 'MESSAGE',
            timestamp: new Date().toISOString(),
            data: {
              id: Date.now(),
              broadcastId: 999,
              userId: userId,
              deliveryStatus: 'DELIVERED',
              readStatus: 'UNREAD',
              createdAt: new Date().toISOString(),
              senderName: 'System',
              content: 'Test message from SSE connection',
              priority: 'NORMAL',
              category: 'TEST',
              broadcastCreatedAt: new Date().toISOString()
            }
          };
          
          const testEvent = `data: ${JSON.stringify(testData)}\n\n`;
          controller.enqueue(new TextEncoder().encode(testEvent));
          console.log(`Test message sent to session: ${sessionId}`);
        }, 1000);
        
        // Send heartbeat every 30 seconds
        const heartbeat = setInterval(() => {
          const heartbeatData = {
            type: 'HEARTBEAT',
            timestamp: new Date().toISOString()
          };
          const heartbeatEvent = `data: ${JSON.stringify(heartbeatData)}\n\n`;
          controller.enqueue(new TextEncoder().encode(heartbeatEvent));
        }, 30000);
        
        // Clean up on connection close
        request.signal.addEventListener('abort', () => {
          console.log(`SSE connection closed for session: ${sessionId}`);
          clearInterval(heartbeat);
          controller.close();
        });
      }
    });

    return new Response(stream, {
      headers: {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache, no-transform',
        'Connection': 'keep-alive',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Cache-Control'
      }
    });
  } catch (error) {
    console.error('Error establishing SSE connection:', error);
    return NextResponse.json(
      { error: 'Failed to establish SSE connection' },
      { status: 500 }
    );
  }
}