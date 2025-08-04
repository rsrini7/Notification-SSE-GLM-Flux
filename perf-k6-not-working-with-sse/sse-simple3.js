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
  // Using an inline array is simpler for this test setup
  return [
    {"username": "user-001"}, {"username": "user-002"},
    {"username": "user-003"}, {"username": "user-004"},
    {"username": "user-005"}, {"username": "user-006"},
    {"username": "user-007"}, {"username": "user-008"},
    {"username": "user-009"}, {"username": "user-010"}
  ];
});

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 10 }, // Hold at 10 users to listen
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration': ['p(95)<1000'],
    'sse_time_to_receive_message': ['p(90)<1000'], // 90% of users should get the message within 1s
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = 'https://localhost';

// This function creates a new broadcast and returns its ID
function createBroadcast() {
  const broadcastPayload = JSON.stringify({
    senderId: "perf-test-admin",
    senderName: "Performance Test",
    content: `Live performance test message at ${new Date().toISOString()}`,
    targetType: "ALL",
    priority: "HIGH",
    category: "LIVE_TEST",
    isImmediate: true
  });
  const params = { headers: { 'Content-Type': 'application/json' } };
  
  let broadcastId = null;
  group('API: Create Broadcast', function() {
      const res = http.post(`${BASE_URL}/api/broadcasts`, broadcastPayload, params);
      if (check(res, { 'Create Broadcast is 200 OK': (r) => r.status === 200 })) {
          broadcastId = res.json().id;
          console.log(`--- BROADCAST CREATED with ID: ${broadcastId} ---`);
      }
  });
  return broadcastId;
}

export default function () {
  const user = users[__VU % users.length];
  const userID = user.username;
  const sessionID = `k6-session-${__VU}-${Date.now()}`;
  const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&sessionId=${sessionID}`;

  const response = sse.open(sseUrl, {}, function (client) {
    let startTime;
    let broadcastCreated = false;

    client.on('open', function open() {
      console.log(`[VU ${__VU}] (${userID}): Connected. Listening for broadcasts...`);
      startTime = Date.now();
    });

    client.on('event', function (event) {
      if (event.data && event.data.trim().startsWith('{')) {
        const parsedData = JSON.parse(event.data);
        if (parsedData.type === 'MESSAGE') {
            const receivedTime = Date.now();
            sseTimeToReceiveMessage.add(receivedTime - startTime);
            console.log(`[VU ${__VU}] (${userID}): Received broadcast! Closing connection.`);
            // Once we receive the message we are waiting for, we can disconnect.
            client.close(); 
        }
      }
    });
    
    client.on('error', (e) => {
      console.error(`[VU ${__VU}] (${userID}): SSE Error: ${e.error()}`);
    });

    // Main VU logic: stay connected and have one user create a broadcast
    group('User Behavior', function() {
      // Keep the connection open to listen
      // Have the first Virtual User (VU=1) create a broadcast after 10 seconds.
      if (__VU === 1 && !broadcastCreated) {
        sleep(10); // Wait for other users to connect
        createBroadcast();
        broadcastCreated = true;
      }

      // All users will wait up to 30 seconds to receive the message
      sleep(30);
    });
    
    client.close();
  });

  check(response, { "SSE Connection successful": (r) => r && r.status === 200 });
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}