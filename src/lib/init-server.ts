// This file runs on the server side only
import { startBroadcastScheduler } from './broadcast-scheduler';

// Initialize server-side services
export function initializeServer() {
  if (typeof window === 'undefined') {
    console.log('Initializing server-side services...');
    
    // Start the broadcast scheduler
    try {
      startBroadcastScheduler();
      console.log('Broadcast scheduler initialized');
    } catch (error) {
      console.error('Failed to initialize broadcast scheduler:', error);
    }
    
    console.log('Server-side services initialized successfully');
  }
}

// Auto-initialize when this module is imported
initializeServer();