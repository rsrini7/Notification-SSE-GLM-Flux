// k6-script.js
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data'; // For sharing user credentials
import sse from "k6/x/sse"
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// --- Metrics ---
const errors = new Rate('errors');

// SSE Metrics
const sseTimeToFirstEvent = new Trend('sse_time_to_first_event', true);
const sseTimeBetweenEvents = new Trend('sse_time_between_events', true);
const sseEventDataSize = new Trend('sse_event_data_size');
const sseEventParseErrors = new Counter('sse_event_parse_errors');
const sseEventParsedSuccessfully = new Rate('sse_event_parsed_successfully');

// --- Load User Credentials (example) ---
const users = new SharedArray('users', function () {
  return JSON.parse(open('./users.json')); // Assuming users.json has [{username, password}]
});

export const options = {
  stages: [
    { duration: '1m', target: 20 },  // Ramp up to 20 users
    { duration: '1m', target: 50 }, // Ramp up to 50 users
    { duration: '1m', target: 80 }, // Ramp up to 80 users
    { duration: '1m', target: 100 }, // Steady state at 100 users
    { duration: '1m', target: 0 },    // Ramp down
  ],
  thresholds: {
    'http_req_failed': ['rate<0.01'], // Less than 1% errors
    'http_req_duration': ['p(95)<1000'], // 95% of requests < 1s
    'sse_time_between_events': ['p(95)<1000'], // 95% of events should arrive within 1s of each other.
    'sse_event_data_size': ['p(95)<1000'], // 95% of event data size should be less than 1000 bytes.
    'sse_event_parse_errors': ['count<100'], // No more than 100 parsing errors in total.
    'sse_event_parsed_successfully': ['rate>0.95'], // 95% of events should be parsed successfully.
    'sse_time_to_first_event': ['p(95)<1000'], // 95% of first events should arrive within 1s of connection.
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = 'https://localhost:8081';

// --- Helper to get a random user ---
function getRandomUser() {
  return users[Math.floor(Math.random() * users.length)];
}

export default function () {
  const user = getRandomUser();
  const userID = user.username;
  
  group('SSE Connection & Activity', function () {
    const sessionID = `k6-session-${Date.now()}`;
    const sseUrl = `${BASE_URL}/api/sse/connect?userId=${userID}&sessionId=${sessionID}`;
    const sseParams = {
      headers: { 'Accept': 'text/event-stream' },
    };

    const response = sse.open(sseUrl, sseParams, function (client) {
        let startTime;
        let lastEventTime;
        let isFirstEvent = true;

        client.on('open', function open() {
            console.log('connected')
            startTime = Date.now();
            lastEventTime = startTime;
        })

        client.on('event', function (event) {
            console.log(`event id=${event.id}, name=${event.name}, data=${event.data}`)

            const receivedTime = Date.now();
      
            // -- Add Trend Samples --
            if (isFirstEvent) {
              sseTimeToFirstEvent.add(receivedTime - startTime);
              isFirstEvent = false;
            }
            sseTimeBetweenEvents.add(receivedTime - lastEventTime);
            sseEventDataSize.add(event.data.length);
            lastEventTime = receivedTime; // Update for the next event

            // Try to parse the data only if it looks like a JSON object
            if (event.data && event.data.trim().startsWith('{')) {
                try {
                    JSON.parse(event.data);
                    sseEventParsedSuccessfully.add(true);
                } catch (e) {
                    sseEventParseErrors.add(1);
                    sseEventParsedSuccessfully.add(false);
                    console.error(`Failed to parse JSON: ${event.data}`);
                }
            } else {
                if(!event.data || event.data.trim() === '') {
                    sseEventParseErrors.add(1);
                    sseEventParsedSuccessfully.add(false);
                } else {
                    sseEventParsedSuccessfully.add(true);
                }
            }

            if (parseInt(event.id) === 3) {
                client.close()
            }
        })

        client.on('error', function (e) {
            console.log('An unexpected error occurred: ', e.error())
        })
    })

    check(response, {"status is 200": (r) => r && r.status === 200})

  });
  sleep(1); // Simulate user think time
}

// Function to generate a plain text summary report at the end of the test
export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    };
}