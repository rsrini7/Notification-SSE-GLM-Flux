import { check, sleep } from 'k6';
import sse from "k6/x/sse";
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const userIDs = [ 'user-001' ];

export const options = {
  stages: [
    { duration: '5s', target: 1 },
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
  
  console.log(`VU ${__VU}: Attempting to connect as ${userID}...`);

  const ssePromise = new Promise((resolve, reject) => {
    const response = sse.open(sseUrl, {}, function (client) {
      let connected = false;

      client.on('open', function open() {
        console.log(`VU ${__VU} (${userID}): SSE connection opened!`);
        connected = true;
      });

      client.on('event', function (event) {
        console.log(`VU ${__VU} (${userID}): Received data: ${event.data}`);
      });

      client.on('error', function (e) {
        console.error(`VU ${__VU} (${userID}): SSE Error: ${e.error()}`);
        if (!connected) {
          reject('Connection failed before opening.');
        }
        client.close();
      });
      
      // Keep the client running to listen for events.
      // We will resolve the promise after a delay.
      sleep(20); // Keep connection alive for 20 seconds.

      client.close();
      console.log(`VU ${__VU} (${userID}): SSE connection closing.`);
      resolve('Test iteration finished.');
    });
    
    check(response, { "Initial SSE HTTP request is successful": (r) => r && r.status === 200 });
    if (response.status !== 200) {
        reject(`Connection failed with status ${response.status}`);
    }
  });

  try {
    const result = ssePromise; // In k6, promises are implicitly awaited at the end of the iteration.
  } catch (e) {
    console.error(`VU ${__VU}: Promise rejected: ${e}`);
  }
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}