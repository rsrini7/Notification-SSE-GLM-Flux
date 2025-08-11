import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import sse from "k6/x/sse";
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// --- Metrics ---
const sseTimeToReceiveMessage = new Trend('sse_time_to_receive_message', true);

// --- Load User Data ---
const users = new SharedArray('users', function () {
  // NOTE: Ensure you have a 'users.json' file in the same directory
  // with the format: [{"username": "user-001"}, {"username": "user-002"}, ...]
  return JSON.parse(open('./users.json'));
});

export const options = {
  stages: [
    { duration: '20s', target: 10 },   // Ramp up to 10 users
    { duration: '40s', target: 10 },   // Hold at 10 users while broadcasts are sent
    { duration: '10s', target: 0 },    // Ramp down
  ],
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration': ['p(95)<1000'],
    'sse_time_to_receive_message': ['p(95)<2000'],
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = 'https://localhost:8081';

// --- Main Test Logic ---
export default function () {
  const user = users[__VU % users.length];
  const userID = user.username;
  // FIX: Use Date.now() to ensure the connection ID is unique for every connection attempt.
  const connectionID = `k6-connection-${__VU}-${Date.now()}`;
  const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&connectionId=${connectionID}`;
  const sseParams = { headers: { 'Accept': 'text/event-stream' } };

  const response = sse.open(sseUrl, sseParams, function (client) {
    let startTime;
    let broadcastCreated = false;

    client.on('open', function open() {
      console.log(`[VU ${__VU}] (${userID}): Connected. Listening for broadcasts...`);
      startTime = Date.now();
    });

    client.on('event', function (event) {
      if (event.data && event.data.trim().startsWith('{')) {
        try {
            const parsedData = JSON.parse(event.data);
            if (parsedData.type === 'MESSAGE') {
                const receivedTime = Date.now();
                sseTimeToReceiveMessage.add(receivedTime - startTime);
                console.log(`[VU ${__VU}] (${userID}): Received broadcast!`);

                // Simulate 70% of users reading the message
                if (Math.random() < 0.7) {
                    sleep(Math.random() * 4 + 1); // Think time: 1-5 seconds
                    
                    group('API: Mark Message as Read', function() {
                        const messageId = parsedData.data.id;
                        const readUrl = `${BASE_URL}/api/sse/read?userId=${userID}&messageId=${messageId}`;
                        const res = http.post(readUrl);
                        check(res, { 'POST /api/sse/read status is 200': (r) => r.status === 200 });
                    });
                }
                // After receiving the message, the user can disconnect.
                client.close();
            }
        } catch (e) {
            console.error(`[VU ${__VU}] (${userID}): Failed to parse JSON: ${event.data}`);
        }
      }
    });
    
    client.on('error', (e) => {
      console.error(`[VU ${__VU}] (${userID}): SSE Error: ${e.error()}`);
    });

    // --- User Behavior Loop ---
    // The first user (VU=1) is responsible for creating a broadcast.
    // This happens *after* other users have had a chance to connect.
    if (__VU === 1 && !broadcastCreated) {
      sleep(10); // Wait 10s for other VUs to connect
      
      group('API: Create Broadcast', function() {
        const broadcastPayload = JSON.stringify({
          senderId: "perf-test-admin",
          senderName: "Performance Test",
          content: `Live test message from VU ${__VU} at ${new Date().toISOString()}`,
          targetType: "ALL",
          priority: "HIGH",
          isImmediate: true
        });
        const params = { headers: { 'Content-Type': 'application/json' } };
        const res = http.post(`${BASE_URL}/api/broadcasts`, broadcastPayload, params);
        check(res, { 'Create Broadcast is 200 OK': (r) => r.status === 200 });
      });
      broadcastCreated = true;
    }

    // All users will wait up to 30 seconds to receive the message before timing out.
    sleep(30);
    client.close();
  });

  check(response, { "SSE Connection successful": (r) => r && r.status === 200 });
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}