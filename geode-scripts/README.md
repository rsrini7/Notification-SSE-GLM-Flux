gfsh>

connect

list regions

query --query="SELECT * FROM /sse-messages"

list durable-cqs --durable-client-id=local_admin-local-0

list durable-cqs --durable-client-id=cluster-a_broadcast-user-service-0

remove --region=/sse-messages --all

query --query="SELECT e.key, e.value FROM /active-group-broadcasts.entries e"
get --key="ROLE:ADMIN" --region=/active-group-broadcasts

query --query="SELECT e.key, e.value FROM /pod-connections.entries e"