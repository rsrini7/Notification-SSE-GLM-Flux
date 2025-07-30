# Kubernetes Deployment Guide for Broadcast Microservice

## Architecture Overview

This broadcast messaging microservice is designed to handle 400,000+ registered users with 30,000+ concurrent SSE connections. The Kubernetes deployment provides high availability, scalability, and monitoring.

## Key Components

### 1. Core Components
- **Deployment**: Manages pod lifecycle with rolling updates
- **Service**: Provides stable network endpoints
- **HPA**: Horizontal Pod Autoscaler for automatic scaling
- **PDB**: Pod Disruption Budget for high availability
- **Network Policy**: Controls network traffic flow

### 2. Supporting Components
- **Service Account & RBAC**: For Kubernetes API access
- **ConfigMap**: Configuration management
- **Kafka Topics**: Event streaming infrastructure
- **Monitoring**: Prometheus metrics and alerts

## Scaling Strategy

### Pod Sizing and Count
- **Initial Replicas**: 3 pods for high availability
- **Max Replicas**: 20 pods for peak load handling
- **Memory per Pod**: 3-5GB (request: 3Gi, limit: 5Gi)
- **CPU per Pod**: 1-2 cores (request: 1000m, limit: 2000m)

### Horizontal Pod Autoscaler (HPA)
The HPA scales based on multiple metrics:

1. **Resource Metrics**:
   - CPU: Scale when > 70% utilization
   - Memory: Scale when > 80% utilization

2. **Custom Metrics**:
   - HTTP Requests: Scale when > 1000 requests/sec per pod
   - SSE Connections: Scale when > 1500 connections per pod

3. **Scaling Behavior**:
   - **Scale Up**: Fast (60s stabilization, 50% increase)
   - **Scale Down**: Conservative (300s stabilization, 10% decrease)

### Capacity Planning
| Metric | Per Pod | Cluster Total (20 pods) |
|--------|---------|------------------------|
| SSE Connections | 1,500 | 30,000 |
| Memory | 5GB | 100GB |
| CPU | 2 cores | 40 cores |
| Network | 1Gbps | 20Gbps |

## Kafka Configuration

### Topic Partitioning
- **Main Topic**: 10 partitions for parallel processing
- **Replication Factor**: 3 for fault tolerance
- **Retention**: 7 days for main events, 24 hours for delivery events

### Consumer Groups
- **Broadcast Service Group**: 3 consumers for parallel processing
- **Partition Assignment**: Automatic Kafka partition assignment
- **Offset Management**: Manual offset commits for at-least-once semantics

## High Availability Features

### Pod Anti-Affinity
```yaml
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
    - weight: 100
      podAffinityTerm:
        labelSelector:
          matchExpressions:
          - key: app
            operator: In
            values:
            - broadcast-microservice
        topologyKey: kubernetes.io/hostname
```

### Pod Disruption Budget
- **Min Available**: 2 pods always available
- **Max Unavailable**: 1 pod during updates/maintenance

### Rolling Update Strategy
- **Max Surge**: 1 additional pod during update
- **Max Unavailable**: 1 pod maximum unavailable

## Network Configuration

### Service Configuration
- **Type**: ClusterIP (internal access only)
- **Session Affinity**: ClientIP for sticky SSE connections
- **Ports**: 8080 (HTTP), 8081 (API)

### Network Policy
- **Ingress**: Allow from ingress, monitoring, and Kafka namespaces
- **Egress**: Allow to Kafka and external services
- **Protocol**: TCP only for required ports

## Monitoring and Observability

### Metrics Collection
- **Prometheus**: Scrapes metrics from `/api/actuator/prometheus`
- **Custom Metrics**: SSE connections, message delivery rates
- **Health Checks**: Liveness and readiness probes

### Alerting Rules
Critical alerts:
- Service down
- Database connection errors
- High memory/CPU usage

Warning alerts:
- High SSE connections
- Kafka consumer lag
- Message delivery failures
- Pod restarts

### Logging
- **Structured Logging**: JSON format for easy parsing
- **Log Rotation**: Daily rotation with 30-day retention
- **Centralized**: Logs collected to centralized logging system

## Resource Management

### Java Heap Configuration
```yaml
env:
- name: JAVA_OPTS
  value: "-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### Garbage Collection
- **GC Algorithm**: G1GC for low pause times
- **Max Pause Time**: 200ms target
- **Heap Size**: 2-4GB range

### Database Connection Pool
- **Max Connections**: 50 connections per pod
- **Batch Size**: 1000 records for bulk operations
- **Timeout**: 30 seconds default

## Deployment Commands

### 1. Apply Configuration
```bash
# Create namespace
kubectl apply -f namespace.yaml

# Apply RBAC
kubectl apply -f service-account.yaml
kubectl apply -f role.yaml
kubectl apply -f role-binding.yaml

# Apply ConfigMap
kubectl apply -f configmap.yaml

# Apply Network Policy
kubectl apply -f network-policy.yaml

# Apply Kafka Topics
kubectl apply -f kafka-topic.yaml

# Apply main resources
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
kubectl apply -f hpa.yaml
kubectl apply -f pod-disruption-budget.yaml
```

### 2. Verify Deployment
```bash
# Check pod status
kubectl get pods -n broadcast-system

# Check HPA status
kubectl get hpa -n broadcast-system

# Check service status
kubectl get svc -n broadcast-system

# Check metrics
kubectl top pods -n broadcast-system
```

### 3. Monitoring Setup
```bash
# Apply monitoring config
kubectl apply -f prometheus-config.yaml -n monitoring

# Check Prometheus targets
kubectl get --raw /api/v1/namespaces/monitoring/services/prometheus:http/proxy/api/v1/targets
```

## Performance Tuning

### JVM Tuning
- **Heap Size**: Adjust based on actual memory usage
- **GC Settings**: Tune for low pause times
- **Thread Pools**: Configure for concurrent processing

### Kafka Tuning
- **Batch Size**: Optimize for throughput vs latency
- **Compression**: Use LZ4 for better performance
- **Retries**: Configure for resilience

### Cache Tuning
- **Caffeine Cache**: Adjust size and expiration
- **Connection Pool**: Optimize for database load
- **SSE Timeouts**: Balance resource usage vs UX

## Disaster Recovery

### Backup Strategy
- **Database**: Regular h2 database backups
- **Configuration**: ConfigMap versioning
- **Kafka**: Topic replication and retention

### Failover Procedure
1. **Pod Failure**: Automatic restart via deployment controller
2. **Node Failure**: Pods reschedule to healthy nodes
3. **Zone Failure**: Multi-zone deployment with anti-affinity

### Rollback Strategy
- **Deployment**: Use deployment rollback capability
- **Configuration**: Revert ConfigMap versions
- **Database**: Restore from backup

## Security Considerations

### Network Security
- **Network Policies**: Restrict traffic to required services
- **TLS**: Encrypt all external communications
- **Authentication**: Service account-based auth

### Application Security
- **Input Validation**: Validate all user inputs
- **SQL Injection**: Use parameterized queries
- **XSS Protection**: Sanitize user-generated content

### Data Protection
- **Sensitive Data**: Encrypt at rest and in transit
- **Access Control**: RBAC for Kubernetes resources
- **Audit Logging**: Log all administrative actions

## Troubleshooting

### Common Issues

1. **High Memory Usage**
   - Check heap size and GC settings
   - Review cache configurations
   - Monitor for memory leaks

2. **SSE Connection Issues**
   - Check network policies
   - Verify service endpoints
   - Monitor connection counts

3. **Kafka Consumer Lag**
   - Check consumer group health
   - Monitor partition assignment
   - Review processing throughput

4. **Database Performance**
   - Monitor connection pool usage
   - Review query performance
   - Check for slow queries

### Debug Commands
```bash
# Check pod logs
kubectl logs -f deployment/broadcast-microservice -n broadcast-system

# Check pod events
kubectl describe pod -l app=broadcast-microservice -n broadcast-system

# Check HPA events
kubectl describe hpa broadcast-microservice-hpa -n broadcast-system

# Check Kafka consumer groups
kubectl exec -it kafka-broker -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group broadcast-service-group
```

This comprehensive Kubernetes deployment ensures the broadcast microservice can handle the target scale of 400,000 users with 30,000 concurrent connections while maintaining high availability, performance, and observability.