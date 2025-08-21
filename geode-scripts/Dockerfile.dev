# FILE: geode-scripts/Dockerfile

# Use an official Eclipse Temurin base image with Java 21
FROM eclipse-temurin:21-jre

# Corrected: Use the official GitHub release URL
ARG GEODE_VERSION=1.15.1
ARG GEODE_DOWNLOAD_URL=https://github.com/apache/geode/releases/download/rel%2Fv${GEODE_VERSION}/apache-geode-${GEODE_VERSION}.tgz

# Set environment variables using the recommended key=value format
ENV GEODE_HOME=/opt/geode
ENV PATH=$GEODE_HOME/bin:$PATH

# Install required packages, create user and directories in the correct order
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl tar && \
    useradd --system --home /geode --user-group geode && \
    mkdir -p $GEODE_HOME /geode /data && \
    chown -R geode:geode /geode $GEODE_HOME /data && \
    rm -rf /var/lib/apt/lists/*

# Switch to the non-root user
USER geode

# Download, extract, and clean up Geode
WORKDIR $GEODE_HOME
RUN curl -fsSL "$GEODE_DOWNLOAD_URL" -o apache-geode.tgz && \
    tar -xzf apache-geode.tgz --strip-components=1 && \
    rm apache-geode.tgz

# Set the default working directory for the container
WORKDIR /geode