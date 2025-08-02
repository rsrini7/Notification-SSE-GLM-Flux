import { check, sleep, group } from 'k6';
import { sse } from 'k6/x/sse';
import http from 'k6/http';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';
import { Trend, Rate } from 'k6/metrics';

// Custom metrics to track SSE-specific events
const sseEventParseErrors = new Rate('sse_event_parse_errors');

// The base URL for your Nginx proxy
const BASE_URL = 'https://localhost:8081';

// Test options: Ramp up to 100 virtual users over 30 seconds,
// then hold that load for 1 minute.
export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '1m', target: 100 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_failed': ['rate<0.01'], // less than 1% of http requests fail
    'sse_event_parse_errors': ['rate<0.01'], // less than 1% of sse events fail to parse
  },
  insecureSkipTLSVerify: true,
};

export default function () {
  // Each virtual user gets a unique ID
  const userID = `k6-user-${__VU}-${__ITER}`;
  const sessionID = `k6-session-${Date.now()}`;
  const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&sessionId=${sessionID}`;

  const response = sse.open(sseUrl, { tags: { test_stream: 'broadcast' } }, function (client) {
    
    client.on('open', function open() {
      console.log(`VU ${__VU}: SSE connection opened!`);

      // --- Start of User Behavior ---
      const heartbeatUrl = `${BASE_URL}/api/sse/heartbeat?userId=${userID}&sessionId=${sessionID}`;
      
      // Send heartbeats every 15 seconds for the duration of the test
      for (let i = 0; i < 4; i++) { // Loop 4 times for a 1-minute test
          sleep(15);
          const heartbeatRes = http.post(heartbeatUrl);
          check(heartbeatRes, { 'Heartbeat successful': (r) => r.status === 200 }, { group: 'heartbeats' });
      }
      // --- End of User Behavior ---

      // Close the connection gracefully after the user's task is done
      client.close();
      console.log(`VU ${__VU}: SSE connection closing.`);
    });

    client.on('event', function (event) {
      // This is called for every message from the server
      console.log(`VU ${__VU}: Received SSE event name=${event.name}, data=${event.data}`);
      if (event.data && event.data.trim().startsWith('{')) {
          try {
              JSON.parse(event.data);
          } catch (e) {
              sseEventParseErrors.add(1);
              console.error(`VU ${__VU}: Failed to parse JSON: ${event.data}`);
          }
      }
    });

    client.on('error', function (e) {
      console.error(`VU ${__VU}: An unexpected error occurred: ${e.error()}`);
    });
  });

  check(response, { 'sse.open request successful': (r) => r && r.status === 200 });
}

// Function to generate a plain text summary report at the end of the test
export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}