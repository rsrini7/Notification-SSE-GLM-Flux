import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import sse from "k6/x/sse";
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// --- Metrics ---
const sseTimeToFirstMessage = new Trend('sse_time_to_first_message', true);

// --- Load User Data ---
const users = new SharedArray('users', function () {
  return JSON.parse(open('./users.json'));
});

export const options = {
  stages: [
    { duration: '20s', target: 10 },   // Ramp up to 10 users to ensure stability
    { duration: '30s', target: 10 },  // Hold at 10 users
    { duration: '10s', target: 0 },    // Ramp down
  ],
  thresholds: {
    'http_req_failed': ['rate<0.01'], // Less than 1% errors
    'http_req_duration{group:::API: Create Broadcast}': ['p(95)<500'], // Create broadcast should be fast
    'http_req_duration{group:::API: Mark Message as Read}': ['p(95)<500'], // Mark as read should be fast
    'sse_time_to_first_message': ['p(95)<2000'], // 95% of users should receive the broadcast within 2s
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = 'https://localhost';

// This function runs once before the test starts.
// It creates the broadcast message that all virtual users will receive.
export function setup() {
  console.log('Setting up test: Creating a new broadcast...');
  
  const broadcastPayload = JSON.stringify({
    senderId: "perf-test-admin",
    senderName: "Performance Test Admin",
    content: `This is a performance test broadcast message ID: ${Date.now()}`,
    targetType: "ALL",
    priority: "NORMAL",
    category: "TEST",
    isImmediate: true
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  const res = http.post(`${BASE_URL}/api/broadcasts`, broadcastPayload, params);
  
  check(res, { 'Setup: Create Broadcast successful': (r) => r.status === 200 });
  
  const broadcastData = res.json();
  console.log(`Broadcast created with ID: ${broadcastData.id}`);
  
  // Pass the created broadcast data to the main test function
  return { broadcastId: broadcastData.id };
}

export default function (data) {
  // Each VU gets a unique user object from the shared array
  const user = users[__VU % users.length];
  const userID = user.username;
  const expectedBroadcastId = data.broadcastId;

  const sessionID = `k6-session-${__VU}-${__ITER}`;
  const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&sessionId=${sessionID}`;
  const sseParams = { headers: { 'Accept': 'text/event-stream' } };

  group(`User Session VU=${__VU} (${userID})`, function () {
    const response = sse.open(sseUrl, sseParams, function (client) {
        let startTime;

        client.on('open', function open() {
            console.log(`VU ${__VU} (${userID}): SSE connection opened. Waiting for broadcast...`);
            startTime = Date.now();
        });

        client.on('event', function (event) {
            if (event.data && event.data.trim().startsWith('{')) {
                try {
                    const parsedData = JSON.parse(event.data);
                    
                    if (parsedData.type === 'MESSAGE' && parsedData.data.broadcastId === expectedBroadcastId) {
                        const receivedTime = Date.now();
                        sseTimeToFirstMessage.add(receivedTime - startTime);
                        console.log(`VU ${__VU} (${userID}): Received test broadcast message.`);
                        
                        // Simulate 70% of users reading the message
                        if (Math.random() < 0.7) {
                            sleep(Math.random() * 5 + 1); // Think time: 1-6 seconds
                            
                            group('API: Mark Message as Read', function() {
                                const messageId = parsedData.data.id;
                                const readUrl = `${BASE_URL}/api/sse/read?userId=${userID}&messageId=${messageId}`;
                                const res = http.post(readUrl);
                                check(res, { 'POST /api/sse/read status is 200': (r) => r.status === 200 });
                            });
                        }
                        // After receiving and processing the one message we care about, close the connection.
                        client.close();
                    }
                } catch (e) {
                    console.error(`VU ${__VU} (${userID}): Failed to parse JSON: ${event.data}`);
                }
            }
        });

        client.on('error', function (e) {
            console.error(`VU ${__VU} (${userID}): An unexpected error occurred: ${e.error()}`);
        });

        // Add a timeout to prevent VUs from hanging forever if they don't receive the message
        sleep(20); // Max wait time of 20 seconds
        client.close();
    });

    check(response, { "SSE connection request successful": (r) => r && r.status === 200 });
  });
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}