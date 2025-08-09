# PowerShell script to connect to Redis running in Docker using redis-cli
# Usage: .\connect-redis.ps1

# Enable verbose output for debugging
$VerbosePreference = "Continue"

# Auto-detect the Redis container name
Write-Verbose "Searching for Redis containers..."
$dockerOutput = & docker ps --format "{{.Names}}\t{{.Image}}"
Write-Verbose "Docker output: $dockerOutput"

$redisContainer = $null
foreach ($line in $dockerOutput) {
    Write-Verbose "Processing line: $line"
    if ($line -match "(.+)\t(.+)") {
        $name = $matches[1]
        $image = $matches[2]
        Write-Verbose "Container: $name, Image: $image"
        if ($image -like "*redis*") {
            Write-Verbose "Found Redis container: $name with image: $image"
            $redisContainer = $name
            break
        }
    }
}

if (-not $redisContainer) {
    Write-Host "No running Redis container found."
    Write-Host "Available containers:"
    & docker ps --format "table {{.Names}}\t{{.Image}}"
    exit 1
}

Write-Host "Found Redis container: $redisContainer"

# Run redis-cli inside the container interactively
Write-Host "Connecting to Redis using redis-cli..."
docker exec -it $redisContainer redis-cli