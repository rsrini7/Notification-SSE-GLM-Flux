# 11. Deployment

## Motivation
Effective deployment strategies are crucial for ensuring the Broadcast Microservice is scalable, resilient, and easy to manage across different environments. We support both local development deployments using Docker Compose and production-grade deployments on Kubernetes.

## Docker Compose Deployment
For local development and testing, Docker Compose provides a convenient way to spin up all necessary services (Kafka, Zookeeper, PostgreSQL, Redis, Nginx, and the Broadcast Microservice itself) with a single command.

### Key Components in `docker-compose.yml`
- **Nginx**: Acts as a reverse proxy and serves the React frontend.
- **Kafka & Zookeeper**: The messaging backbone.
- **PostgreSQL**: The primary database for persistent data.
- **Redis**: Used for caching and session management.
- **Broadcast Microservice**: The main Spring Boot application.

### Example `docker-compose.yml` Snippet
```yaml:docker-compose.yml
version: '3.8'
services:
  nginx:
    image: nginx:latest
    ports:
      - "443:443"
    volumes:
      - ./broadcast-frontend/nginx.conf:/etc/nginx/nginx.conf
      - ./broadcast-frontend/localhost.pem:/etc/nginx/certs/localhost.pem
      - ./broadcast-frontend/localhost-key.pem:/etc/nginx/certs/localhost-key.pem
    depends_on:
      - kafka
      - postgres
      - redis

  kafka:
    image: confluentinc/cp-kafka:7.3.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: true

  zookeeper:
    image: confluentinc/cp-zookeeper:7.3.0
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000

  postgres:
    image: postgres:15
    environment:
      POSTGRES_USER: user
      POSTGRES_PASSWORD: password
      POSTGRES_DB: broadcastdb
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

volumes:
  postgres_data:
  redis_data:
```

## Kubernetes Deployment
For production environments, the microservice is deployed on Kubernetes, leveraging its features for orchestration, scaling, and high availability. The deployment is managed using Kustomize for environment-specific configurations.

### Key Kubernetes Resources
- **Deployment**: Defines how the Broadcast Microservice pods are deployed and managed, including replica counts, rolling updates, and resource limits.
- **Service**: Exposes the microservice within the cluster.
- **ConfigMap**: Manages configuration data.
- **PersistentVolumeClaim (PVC)**: For persistent storage (e.g., PostgreSQL data).
- **Horizontal Pod Autoscaler (HPA)**: Automatically scales the number of pods based on CPU utilization or other metrics.
- **NetworkPolicy**: Controls network access to and from the pods.

### Example Kubernetes Deployment Snippet
```yaml:broadcast-microservice/k8s/base/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: broadcast-microservice
  namespace: broadcast-system
  labels:
    app: broadcast-microservice
    version: v1
spec:
  replicas: 3
  selector:
    matchLabels:
      app: broadcast-microservice
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 1
  template:
    metadata:
      labels:
        app: broadcast-microservice
        version: v1
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/api/actuator/prometheus"
    spec:
      serviceAccountName: broadcast-service-account
      containers:
      - name: broadcast-microservice
        image: broadcast-microservice:1.0.0
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: http
          protocol: TCP
        - containerPort: 8081
          name: api
          protocol: TCP
        env:
        - name: POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        - name: POD_IP
          valueFrom:
            fieldRef:
              fieldPath: status.podIP
        - name: BROADCAST_POD_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: JAVA_OPTS
          value: "-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2"
        - name: SPRING_PROFILES_ACTIVE
          value: "k8s,redis"
        resources:
          requests:
            memory: "3Gi"
            cpu: "1000m"
          limits:
            memory: "5Gi"
            cpu: "2000m"
        livenessProbe:
          httpGet:
            path: /api/actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
          timeoutSeconds: 5
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /api/actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        volumeMounts:
        - name: config-volume
          mountPath: /config
        - name: logs
          emptyDir: {}
      volumes:
      - name: config-volume
        configMap:
          name: broadcast-config
      - name: logs
        emptyDir: {}
      terminationGracePeriodSeconds: 60
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
      tolerations:
      - key: "broadcast-node"
        operator: "Equal"
        value: "true"
        effect: "NoSchedule"
      nodeSelector:
        node-role.kubernetes.io/worker: "true"
```