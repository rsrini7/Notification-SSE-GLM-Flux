#!/bin/sh

# Set default environment to "prod" if not specified
NGINX_ENV=${NGINX_ENV:-prod}

echo "--- Starting Nginx in $NGINX_ENV mode ---"

# Substitute the backend URL into a temporary file
envsubst '$BACKEND_URL' < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf.tmp

# --- Start of Dynamic Block Generation ---

# Dynamically set the resolver config
if [ "$NGINX_ENV" = "dev" ]; then
  RESOLVER_CONFIG="resolver 127.0.0.11;"
else
  RESOLVER_CONFIG="" # This is not needed in Kubernetes
fi

# Dynamically set the frontend location block
if [ "$NGINX_ENV" = "dev" ]; then
  FRONTEND_CONFIG='
    location / {
        proxy_pass https://host.docker.internal:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
    }'
else
  FRONTEND_CONFIG='
    location / {
        root   /usr/share/nginx/html;
        index  index.html index.htm;
        try_files $uri $uri/ /index.html;
    }'
fi

# --- End of Dynamic Block Generation ---

# Use awk to substitute both placeholders into the final config file
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