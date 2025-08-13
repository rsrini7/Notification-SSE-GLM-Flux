#!/bin/sh

# Default to 'prod' if NGINX_ENV is not set
NGINX_ENV=${NGINX_ENV:-prod}
echo "--- Starting Nginx in $NGINX_ENV mode ---"

# Determine which config and cert files to use based on the environment
if [ "$NGINX_ENV" = "dev" ]; then
  SOURCE_CONFIG="/etc/nginx/nginx.dev.conf"
  CERT_FILE_NAME="localhost.pem"
  KEY_FILE_NAME="localhost-key.pem"
else
  SOURCE_CONFIG="/etc/nginx/nginx.prod.conf"
  CERT_FILE_NAME="tls.crt"
  KEY_FILE_NAME="tls.key"
fi

DEST_CONFIG="/etc/nginx/nginx.conf"

# Export variables so envsubst can find them
export ADMIN_BACKEND_URL
export USER_BACKEND_URL
export CERT_FILE_NAME
export KEY_FILE_NAME

# Substitute environment variables in the chosen config file
envsubst '$ADMIN_BACKEND_URL,$USER_BACKEND_URL,$CERT_FILE_NAME,$KEY_FILE_NAME' < "$SOURCE_CONFIG" > "$DEST_CONFIG"

echo "--- Final Nginx Configuration ---"
cat "$DEST_CONFIG"
echo "---------------------------------"

# Execute the main Nginx process
exec nginx -g 'daemon off;'