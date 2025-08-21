# reset-kafka-dev.ps1
<#
.SYNOPSIS
    Purges all Kafka topics and resets all consumer group offsets.
.DESCRIPTION
    This is a complete reset script for a local development environment. It performs two actions:
    1. Purges all messages from user-created topics by temporarily setting retention to 1ms.
    2. Resets the offsets for all consumer groups to the beginning of each topic,
       which clears any processing loops caused by invalid or remembered offsets.
#>

# --- Configuration ---
$KafkaContainerName = "kafka-broker-dev"
$InternalBrokerAddress = "localhost:9092"
$CleanupWaitSeconds = 5

# --- Script Logic ---

# Step 1: Purge Topics (Copied from previous script)
Write-Host "--- Step 1: Purging all topic messages ---" -ForegroundColor Yellow
try {
    # Find the container
    $container = docker ps --filter "name=$KafkaContainerName" --format "{{.Names}}" | Select-Object -First 1
    if (-not $container) {
        throw "Kafka container '$KafkaContainerName' not found."
    }

    # Get topics and filter out internal ones
    $topicList = docker exec $container kafka-topics --bootstrap-server $InternalBrokerAddress --list
    $topicsToPurge = $topicList | Where-Object { $_ -notlike "_*" }

    if ($topicsToPurge.Length -eq 0) {
        Write-Host "No user-created topics found to purge."
    } else {
        # Set retention to 1ms
        foreach ($topic in $topicsToPurge) {
            Write-Host "Purging topic: $topic"
            docker exec $container kafka-configs --bootstrap-server $InternalBrokerAddress --topic $topic --alter --add-config "retention.ms=1"
        }

        # Wait and then restore retention by deleting the override
        Write-Host "Waiting $CleanupWaitSeconds seconds for cleanup..." -ForegroundColor Cyan
        Start-Sleep -Seconds $CleanupWaitSeconds
        foreach ($topic in $topicsToPurge) {
            Write-Host "Restoring default retention for topic: $topic"
            docker exec $container kafka-configs --bootstrap-server $InternalBrokerAddress --topic $topic --alter --delete-config "retention.ms"
        }
    }
    Write-Host "Topic purge complete." -ForegroundColor Green
}
catch {
    Write-Host "ERROR during topic purge: $_" -ForegroundColor Red
    exit 1
}

# Step 2: Reset Consumer Group Offsets
Write-Host "`n--- Step 2: Resetting all consumer group offsets ---" -ForegroundColor Yellow
try {
    # List all consumer groups
    $groupList = docker exec $KafkaContainerName kafka-consumer-groups --bootstrap-server $InternalBrokerAddress --list

    if ($groupList.Length -eq 0) {
        Write-Host "No consumer groups found to reset."
        exit 0
    }

    foreach ($group in $groupList) {
        Write-Host "Resetting offsets for group '$group' on all topics..."
        # The command resets offsets for the specified topics (--to-earliest means to the beginning)
        docker exec $KafkaContainerName kafka-consumer-groups --bootstrap-server $InternalBrokerAddress --group $group --reset-offsets --to-earliest --all-topics --execute
    }
}
catch {
    Write-Host "ERROR during consumer group reset: $_" -ForegroundColor Red
    exit 1
}

Write-Host "`n✅ Kafka has been fully reset. You can now start your services." -ForegroundColor Green