import sse from "k6/x/sse";
import { check, sleep } from "k6";

export default function () {
    const url = "https://localhost:8081/api/user/sse/connect?userId=user-001&connectionId=k6-connection-1-1";
    const params = {};

    const response = sse.open(url, params, function (client) {
        client.on('open', function open() {
            console.log('connected');
        });

        client.on('event', function (event) {
            console.log(`event id=${event.id}, name=${event.name}, data=${event.data}`);
            sleep(5);
            client.close();
        });

        client.on('error', function (e) {
            console.log('An unexpected error occurred: ', e.error());
        });
    });

    check(response, { "status is 200": (r) => r && r.status === 200 });
}