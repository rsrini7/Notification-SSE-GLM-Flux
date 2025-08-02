// k6-script.js
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import sse from "k6/x/sse";
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// --- Metrics ---
const errors = new Rate('errors');
const sseTimeToFirstEvent = new Trend('sse_time_to_first_event', true);
const sseEventParseErrors = new Counter('sse_event_parse_errors');
const sseEventParsedSuccessfully = new Rate('sse_event_parsed_successfully');

// START OF CHANGE: Define a list of known user IDs for the test
const userIDs = [
    'user-001', 'user-002', 'user-003', 'user-004', 'user-005', 
    'user-006', 'user-007', 'user-008', 'user-009', 'user-010'
];
// END OF CHANGE

export const options = {
  stages: [
    { duration: '5s', target: 20 },   // Ramp up to 20 users
    { duration: '5s', target: 50 },  // Ramp up to 50 users
    { duration: '10s', target: 80 },  // Steady state at 80 users
    { duration: '10s', target: 0 },    // Ramp down
  ],
  thresholds: {
    'http_req_failed': ['rate<0.01'], // Less than 1% errors
    'http_req_duration': ['p(95)<1500'], // 95% of requests < 1.5s
    'sse_event_parsed_successfully': ['rate>0.95'],
    'sse_time_to_first_event': ['p(95)<1000'],
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = 'https://localhost';

// --- Helper to get a random user ID ---
function getRandomUser() {
  return userIDs[Math.floor(Math.random() * userIDs.length)];
}

export default function () {
  const userID = getRandomUser();
  
  group('User Session: SSE and API Calls', function () {
    const sessionID = `k6-session-${Date.now()}`;
    const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&sessionId=${sessionID}`;
    const sseParams = {
      headers: { 'Accept': 'text/event-stream' },
    };

    const response = sse.open(sseUrl, sseParams, function (client) {
        let startTime;
        let isFirstEvent = true;

        client.on('open', function open() {
            console.log(`VU ${__VU} (${userID}): SSE connection opened.`);
            startTime = Date.now();

            // START OF CHANGE: Simulate user fetching existing messages after connecting
            group('API: Get User Messages', function() {
                const messagesUrl = `${BASE_URL}/api/user/messages?userId=${userID}`;
                const res = http.get(messagesUrl);
                check(res, { 'GET /api/user/messages status is 200': (r) => r.status === 200 });
            });
            // END OF CHANGE
        });

        client.on('event', function (event) {
            const receivedTime = Date.now();

            if (isFirstEvent) {
                sseTimeToFirstEvent.add(receivedTime - startTime);
                isFirstEvent = false;
            }

            if (event.data && event.data.trim().startsWith('{')) {
                try {
                    const parsedData = JSON.parse(event.data);
                    sseEventParsedSuccessfully.add(true);

                    // START OF CHANGE: Simulate user reading a message
                    if (parsedData.type === 'MESSAGE') {
                        console.log(`VU ${__VU} (${userID}): Received a broadcast message.`);
                        // Simulate a 50% chance the user reads the message
                        if (Math.random() < 0.5) {
                            sleep(Math.random() * 5 + 2); // Think time: 2-7 seconds
                            
                            group('API: Mark Message as Read', function() {
                                const messageId = parsedData.data.id;
                                const readUrl = `${BASE_URL}/api/sse/read?userId=${userID}&messageId=${messageId}`;
                                const res = http.post(readUrl);
                                check(res, { 'POST /api/sse/read status is 200': (r) => r.status === 200 });
                                console.log(`VU ${__VU} (${userID}): Marked message ${messageId} as read.`);
                            });
                        }
                    }
                    // END OF CHANGE

                } catch (e) {
                    sseEventParseErrors.add(1);
                    sseEventParsedSuccessfully.add(false);
                    console.error(`Failed to parse JSON: ${event.data}`);
                }
            }
        });

        client.on('error', function (e) {
            console.error(`An unexpected error occurred: ${e.error()}`);
        });

        // Keep the connection open for a random time between 30 and 60 seconds
        sleep(30 + Math.random() * 30);
        client.close();
        console.log(`VU ${__VU} (${userID}): SSE connection closing.`);
    });

    check(response, { "SSE connection request successful (status 200)": (r) => r && r.status === 200 });
  });
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}