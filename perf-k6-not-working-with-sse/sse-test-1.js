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
  return [
    {"username": "user-001"}, {"username": "user-002"},
    {"username": "user-003"}, {"username": "user-004"},
    {"username": "user-005"}, {"username": "user-006"},
    {"username": "user-007"}, {"username": "user-008"},
    {"username": "user-009"}, {"username": "user-010"}
  ];
});

export const options = {
  scenarios: {
    listeners: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 10 }, 
        { duration: '40s', target: 10 }, 
      ],
      exec: 'listen',
      gracefulRampDown: '10s',
    },
    broadcaster: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 3,
      startTime: '15s', 
      exec: 'broadcast',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration': ['p(95)<1000'],
    'sse_time_to_receive_message': ['p(95)<2000'],
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = 'https://localhost:8081';

// --- Broadcaster Logic ---
export function broadcast() {
  console.log(`--- Broadcaster VU creating a new broadcast message... ---`);
  const broadcastPayload = JSON.stringify({
    senderId: "perf-test-admin",
    senderName: "Performance Test",
    content: `Live performance test message at ${new Date().toISOString()}`,
    targetType: "ALL", priority: "HIGH", isImmediate: true
  });
  const params = { headers: { 'Content-Type': 'application/json' } };
  const res = http.post(`${BASE_URL}/api/broadcasts`, broadcastPayload, params);
  check(res, { 'Create Broadcast is 200 OK': (r) => r.status === 200 });
  sleep(15); 
}

// --- Listener Logic ---
export function listen() { 
  const user = users[(__VU - 1) % users.length]; 
  const userID = user.username;
  const connectionID = `k6-connection-${__VU}-${Date.now()}`;
  const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&connectionId=${connectionID}`;

  const response = sse.open(sseUrl, {}, function (client) {
    let startTime;

    client.on('open', function open() {
      console.log(`[Listener VU ${__VU}] (${userID}): Connected. Listening...`);
      startTime = Date.now();
      
      // Keep the user connected and listening for 35 seconds
      sleep(35);
      
      // Close the connection after the listening period is over
      console.log(`[Listener VU ${__VU}] (${userID}): Session finished. Closing client.`);
      client.close();
    });

    client.on('event', function (event) {
      if (event.data && event.data.trim().startsWith('{')) {
        const parsedData = JSON.parse(event.data);
        if (parsedData.type === 'MESSAGE') {
            const receivedTime = Date.now();
            sseTimeToReceiveMessage.add(receivedTime - startTime);
            console.log(`[Listener VU ${__VU}] (${userID}): Received broadcast!`);
        } else if (parsedData.type === 'HEARTBEAT') {
            console.log(`[Listener VU ${__VU}] (${userID}): Received heartbeat.`);
        }
      }
    });
    
    client.on('error', (e) => {
      console.error(`[Listener VU ${__VU}] (${userID}): SSE Error: ${e.error()}`);
    });
  });

  check(response, { "SSE Connection successful": (r) => r && r.status === 200 });
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}