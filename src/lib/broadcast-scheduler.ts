import { db } from '@/lib/db';
import { deliverBroadcastToUsers } from '@/lib/sse';

// Global variable to track the scheduler interval
declare global {
  var schedulerInterval: NodeJS.Timeout | null;
}

// Initialize scheduler if it doesn't exist
if (!global.schedulerInterval) {
  global.schedulerInterval = null;
}

export class BroadcastScheduler {
  private static instance: BroadcastScheduler;
  private checkInterval: NodeJS.Timeout | null = null;
  private readonly CHECK_FREQUENCY_MS = 30000; // Check every 30 seconds

  private constructor() {}

  public static getInstance(): BroadcastScheduler {
    if (!BroadcastScheduler.instance) {
      BroadcastScheduler.instance = new BroadcastScheduler();
    }
    return BroadcastScheduler.instance;
  }

  public start(): void {
    if (this.checkInterval) {
      console.log('Broadcast scheduler is already running');
      return;
    }

    console.log('Starting broadcast scheduler...');
    
    // Check immediately on start
    this.checkAndDeliverScheduledBroadcasts();
    
    // Set up interval to check periodically
    this.checkInterval = setInterval(() => {
      this.checkAndDeliverScheduledBroadcasts();
    }, this.CHECK_FREQUENCY_MS);

    // Store the interval globally to persist across module reloads
    global.schedulerInterval = this.checkInterval;

    console.log(`Broadcast scheduler started (checking every ${this.CHECK_FREQUENCY_MS / 1000} seconds)`);
  }

  public stop(): void {
    if (this.checkInterval) {
      clearInterval(this.checkInterval);
      this.checkInterval = null;
      global.schedulerInterval = null;
      console.log('Broadcast scheduler stopped');
    }
  }

  private async checkAndDeliverScheduledBroadcasts(): Promise<void> {
    try {
      const now = new Date();
      console.log(`Checking for scheduled broadcasts at ${now.toISOString()}`);

      // Find broadcasts that:
      // 1. Are scheduled (isImmediate: false) OR are immediate but have scheduled times and haven't been delivered
      // 2. Have start time that has passed
      // 3. Haven't been delivered yet (no delivery records or totalDelivered is 0)
      const scheduledBroadcasts = await db.broadcast.findMany({
        where: {
          startTime: {
            lte: now // Start time is less than or equal to now
          },
          OR: [
            // Scheduled broadcasts that haven't been delivered
            {
              isImmediate: false,
              OR: [
                { totalDelivered: 0 }, // No deliveries yet
                { 
                  totalDelivered: {
                    lt: 1000 // Assuming a reasonable max
                  }
                }
              ]
            },
            // Immediate broadcasts with scheduled times that haven't been delivered
            {
              isImmediate: true,
              totalDelivered: 0,
              startTime: {
                lt: now // Start time is in the past (not equal to now, to avoid race conditions)
              }
            }
          ]
        }
      });

      console.log(`Found ${scheduledBroadcasts.length} scheduled broadcasts ready for delivery`);

      for (const broadcast of scheduledBroadcasts) {
        try {
          console.log(`Delivering scheduled broadcast ${broadcast.id} (scheduled for ${broadcast.startTime})`);

          // Transform the broadcast to match expected format
          const transformedBroadcast = {
            ...broadcast,
            targetIds: JSON.parse(broadcast.targetIds || '[]'),
            id: parseInt(broadcast.id.replace(/[^\d]/g, '')) || Math.floor(Math.random() * 1000000),
            startTime: broadcast.startTime.toISOString(),
            endTime: broadcast.endTime ? broadcast.endTime.toISOString() : undefined
          };

          // Deliver the broadcast to users
          await deliverBroadcastToUsers({
            ...transformedBroadcast,
            id: broadcast.id, // Use the original database ID for delivery
            timestamp: Date.now()
          });

          // Update the broadcast to mark it as processed
          await db.broadcast.update({
            where: { id: broadcast.id },
            data: {
              totalDelivered: {
                increment: 1 // This will be incremented further by the delivery tracking
              }
            }
          });

          console.log(`Scheduled broadcast ${broadcast.id} delivered successfully`);
        } catch (error) {
          console.error(`Failed to deliver scheduled broadcast ${broadcast.id}:`, error);
        }
      }

      // Also check for broadcasts that should be marked as expired
      await this.checkExpiredBroadcasts();
    } catch (error) {
      console.error('Error in broadcast scheduler check:', error);
    }
  }

  private async checkExpiredBroadcasts(): Promise<void> {
    try {
      const now = new Date();
      
      // Find broadcasts that have end time in the past
      const expiredBroadcasts = await db.broadcast.findMany({
        where: {
          endTime: {
            lte: now, // End time is less than or equal to now
            not: null // Must have an end time
          }
        }
      });

      console.log(`Found ${expiredBroadcasts.length} expired broadcasts`);

      // In a real system, you might want to update their status or perform cleanup
      // For now, just log them
      for (const broadcast of expiredBroadcasts) {
        console.log(`Broadcast ${broadcast.id} has expired (ended at ${broadcast.endTime})`);
      }
    } catch (error) {
      console.error('Error checking expired broadcasts:', error);
    }
  }

  public getStatus(): { isRunning: boolean; nextCheck?: Date } {
    return {
      isRunning: this.checkInterval !== null,
      nextCheck: this.checkInterval ? new Date(Date.now() + this.CHECK_FREQUENCY_MS) : undefined
    };
  }
}

// Export singleton instance
export const broadcastScheduler = BroadcastScheduler.getInstance();

// Function to start the scheduler (can be called from anywhere)
export function startBroadcastScheduler(): void {
  broadcastScheduler.start();
}

// Function to stop the scheduler
export function stopBroadcastScheduler(): void {
  broadcastScheduler.stop();
}

// Auto-start the scheduler when this module is imported
if (typeof window === 'undefined') { // Only run on server side
  // Start the scheduler after a short delay to ensure the app is fully initialized
  setTimeout(() => {
    startBroadcastScheduler();
    console.log('Broadcast scheduler auto-started');
  }, 5000);
}