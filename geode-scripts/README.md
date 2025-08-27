gfsh>

connect

list regions

list clients

list durable-cqs --durable-client-id=cluster-a_broadcast-user-service-0

remove --region=/sse-messages --all

get --key="ROLE:ADMIN" --region=/user-connections

query --query="SELECT entry.key, msg FROM /pending-events.entrySet entry, entry.value msg WHERE msg.broadcastId = 1"


---

query --query="SELECT e.key, e.value FROM /connection-heartbeat.entries e"
query --query="SELECT e.key, e.value FROM /broadcast-content.entries e"
query --query="SELECT e.key, e.value FROM /pending-events.entries e"
query --query="SELECT e.key, e.value FROM /user-connections.entries e"
query --query="SELECT e.key, e.value FROM /sse-messages.entries e"

query --query="SELECT * FROM /sse-messages"

