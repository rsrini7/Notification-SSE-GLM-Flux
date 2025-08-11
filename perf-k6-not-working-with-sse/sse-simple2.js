import { check, sleep } from 'k6';
import sse from "k6/x/sse";
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const userIDs = [ 'user-001', 'user-002', 'user-003', 'user-004', 'user-005' ];

export const options = {
  stages: [
    { duration: '30s', target: 5 },
  ],
  insecureSkipTLSVerify: true,
};

const BASE_URL = 'https://localhost';

function getRandomUser() {
  return userIDs[Math.floor(Math.random() * userIDs.length)];
}

export default function () {
  const userID = getRandomUser();
  const connectionID = `k6-connection-${__VU}-${__ITER}`;
  const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&connectionId=${connectionID}`;
  
  console.log(`[VU ${__VU}] Attempting to connect as ${userID}...`);

  // The sse.open function is blocking. It will not return until the code inside this
  // callback, including the sleep(), is finished. This keeps the VU alive.
  const response = sse.open(sseUrl, {}, function (client) {
    
    client.on('open', function open() {
      // This log will now appear.
      console.log(`[VU ${__VU}] (${userID}): SSE connection opened!`);
    });

    client.on('event', function (event) {
      // This log will appear for every message, including heartbeats.
      console.log(`[VU ${__VU}] (${userID}): Received event data: ${event.data}`);
    });

    client.on('error', function (e) {
      console.error(`[VU ${__VU}] (${userID}): SSE Error: ${e.error()}`);
    });

    // Keep the connection open for 15 seconds to listen for events.
    sleep(15);

    // After the sleep, we close the connection and the VU iteration ends.
    client.close();
    console.log(`[VU ${__VU}] (${userID}): SSE connection closing.`);
  });
  
  check(response, { "Initial HTTP connection request was successful": (r) => r && r.status === 200 });

  if (!response || response.status !== 200) {
    console.error(`[VU ${__VU}] FAILED: Initial connection returned status ${response ? response.status : 'undefined'}`);
  }
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}