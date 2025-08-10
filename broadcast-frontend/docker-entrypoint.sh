#!/bin/sh

# Set default environment to "prod" if not specified
NGINX_ENV=${NGINX_ENV:-prod}

echo "--- Starting Nginx in $NGINX_ENV mode ---"

# --- Start of Dynamic Variable Generation ---

# Set variables based on the environment
if [ "$NGINX_ENV" = "dev" ]; then
  # Docker Compose / Local Dev settings
  RESOLVER_CONFIG="resolver 127.0.0.11;"
  CERT_FILE_NAME="localhost.pem"
  KEY_FILE_NAME="localhost-key.pem"
  FRONTEND_CONFIG='
    location / {
        proxy_pass https://host.docker.internal:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
    }'
else
  # Kubernetes / Production settings
  RESOLVER_CONFIG="" # Not needed in Kubernetes
  CERT_FILE_NAME="tls.crt"
  KEY_FILE_NAME="tls.key"
  FRONTEND_CONFIG='
    location / {
        root   /usr/share/nginx/html;
        index  index.html index.htm;
        try_files $uri $uri/ /index.html;
    }'
fi

# --- End of Dynamic Variable Generation ---

# Substitute the simple variables ($VAR) into a temporary file
# The variables to be substituted must be exported for envsubst to see them
export BACKEND_URL
export CERT_FILE_NAME
export KEY_FILE_NAME
envsubst '$BACKEND_URL,$CERT_FILE_NAME,$KEY_FILE_NAME' < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf.tmp

# Use awk to substitute the multi-line blocks into the final config file
awk \
  -v resolver_config="$RESOLVER_CONFIG" \
  -v frontend_config="$FRONTEND_CONFIG" \
'{
  sub("##RESOLVER_DIRECTIVE##", resolver_config);
  sub("##FRONTEND_LOCATION_BLOCK##", frontend_config);
  print
}' /etc/nginx/nginx.conf.tmp > /etc/nginx/nginx.conf

rm /etc/nginx/nginx.conf.tmp

echo "--- Final Nginx Configuration ---"
cat /etc/nginx/nginx.conf
echo "---------------------------------"

# Start Nginx in the foreground
exec nginx -g 'daemon off;'