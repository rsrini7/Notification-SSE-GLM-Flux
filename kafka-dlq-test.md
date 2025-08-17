Step 1: Get a Shell Inside the Kafka Docker Container

docker exec -it rsrini7-notification-sse-glm-flux-kafka-1 /bin/bash

Step 2: Use the Kafka Console Producer to Send a Message

kafka-console-producer \
--bootstrap-server localhost:9092 \
--topic broadcast-user-service-dlt \
--property "parse.key=true" \
--property "key.separator=:" \
--property "headers=dlt-original-topic:broadcast-user-service,dlt-exception-message:'Manual Test: Invalid JSON payload from CLI',dlt-exception-stacktrace:'N/A'"

Step 3: Send the Message Payload

Paste the following line into the prompt and press Enter:

test-key:{"eventId":"cli-test-123","broadcastId":999,"userId":"user-dlt","eventType":"CREATED","podId":"pod-cli","timestamp":"2025-08-01T10:00:00Z","message":"This is a test message sent directly to the DLT."}