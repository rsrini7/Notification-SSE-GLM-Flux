# FILE: geode-scripts/Dockerfile

FROM eclipse-temurin:21-jre

ARG GEODE_VERSION=1.15.1
ARG GEODE_DOWNLOAD_URL=https://github.com/apache/geode/releases/download/rel%2Fv${GEODE_VERSION}/apache-geode-${GEODE_VERSION}.tgz

ENV GEODE_HOME=/opt/geode
ENV PATH=$GEODE_HOME/bin:$PATH

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl tar && \
    useradd --system --home /geode --user-group geode && \
    mkdir -p $GEODE_HOME /geode /data /geode-scripts /geode-server/classpath && \
    chown -R geode:geode /geode $GEODE_HOME /data /geode-scripts /geode-server && \
    rm -rf /var/lib/apt/lists/*

# Copy the scripts and JAR file into the image
USER root
COPY *.sh /geode-scripts/
COPY broadcast-geode-shared-1.0.0.jar /geode-server/classpath/app.jar
RUN chmod +x /geode-scripts/*.sh && \
    chown -R geode:geode /geode-scripts /geode-server

USER geode
WORKDIR $GEODE_HOME
RUN curl -fsSL "$GEODE_DOWNLOAD_URL" -o apache-geode.tgz && \
    tar -xzf apache-geode.tgz --strip-components=1 && \
    rm apache-geode.tgz

WORKDIR /geode