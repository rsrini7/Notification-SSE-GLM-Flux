gfsh>

connect

list regions

list clients

list durable-cqs --durable-client-id=cluster-a_broadcast-user-service-0

remove --region=/broadcast-content.entries --all

get --key="ROLE:ADMIN" --region=/user-connections

query --query="SELECT entry.key, msg FROM /user-messages-inbo.entrySet entry, entry.value msg WHERE msg.broadcastId = 1"


---

query --query="SELECT e.key, e.value FROM /sse-user-messages.entries e"
query --query="SELECT e.key, e.value FROM /sse-group-messages.entries e"

query --query="SELECT e.key, e.value FROM /connection-heartbeat.entries e"
query --query="SELECT e.key, e.value FROM /user-connections.entries e"

query --query="SELECT e.key, e.value FROM /broadcast-content.entries e"
query --query="SELECT e.key, e.value FROM /user-messages-inbox.entries e"

get --key="user-002" --region="/user-messages-inbox"

query --query="SELECT e as messages FROM /user-messages-inbox.entries e WHERE e.key = 'user-002'"