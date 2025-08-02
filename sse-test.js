import { check, sleep } from 'k6';
import { sse } from 'k6/x/sse';
import http from 'k6/http';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// The base URL for your Nginx proxy
const BASE_URL = 'https://localhost';

// Test options: Ramp up to 100 virtual users over 30 seconds,
// then hold that load for 2 minutes.
export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '2m', target: 100 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_failed': ['rate<0.01'], // less than 1% of requests fail
  },
};

export default function () {
  // Each virtual user gets a unique ID
  const userID = `k6-user-${__VU}-${__ITER}`;
  const sessionID = `k6-session-${Date.now()}`;
  const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&sessionId=${sessionID}`;
  const heartbeatUrl = `${BASE_URL}/api/sse/heartbeat?userId=${userID}&sessionId=${sessionID}`;

  // Establish the SSE connection
  const res = sse(sseUrl, {}, function (event) {
    // This function is called for every message received from the server.
    if (event.data) {
      try {
        const data = JSON.parse(event.data);
        
        // Log the type of event received
        console.log(`VU ${__VU}: Received SSE event of type: ${data.type}`);
        
        // Example check: ensure message events have content
        if (data.type === 'MESSAGE') {
          check(data, {
            'message has content': (d) => d.data && d.data.content.length > 0,
          });
        }
      } catch (e) {
        console.error(`VU ${__VU}: Failed to parse JSON: ${event.data}`);
      }
    }
  });
  
  // Check if the initial SSE connection was successful (HTTP status 200)
  check(res, { 'SSE connection successful': (r) => r && r.status === 200 });

  // Simulate user behavior after connecting
  if (res && res.status === 200) {
    // Send heartbeats every 15 seconds for 2 minutes (8 times)
    for (let i = 0; i < 8; i++) {
        sleep(15); // Wait 15 seconds
        const heartbeatRes = http.post(heartbeatUrl);
        check(heartbeatRes, { 'Heartbeat successful': (r) => r.status === 200 });
    }
  }
}

// Function to generate a plain text summary report at the end of the test
export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}