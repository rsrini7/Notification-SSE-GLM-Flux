#!/bin/sh

NGINX_ENV=${NGINX_ENV:-prod}
echo "--- Starting Nginx in $NGINX_ENV mode ---"

if [ "$NGINX_ENV" = "dev" ];
then
  RESOLVER_CONFIG="resolver 127.0.0.11;"
  CERT_FILE_NAME="localhost.pem"
  KEY_FILE_NAME="localhost-key.pem"
else
  RESOLVER_CONFIG=""
  CERT_FILE_NAME="tls.crt"
  KEY_FILE_NAME="tls.key"
fi

# UPDATED: Export and substitute both new backend URLs
export ADMIN_BACKEND_URL
export USER_BACKEND_URL
export CERT_FILE_NAME
export KEY_FILE_NAME
envsubst '$ADMIN_BACKEND_URL,$USER_BACKEND_URL,$CERT_FILE_NAME,$KEY_FILE_NAME' < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf.tmp

awk -v resolver_config="$RESOLVER_CONFIG" '{
  sub("##RESOLVER_DIRECTIVE##", resolver_config);
print
}' /etc/nginx/nginx.conf.tmp > /etc/nginx/nginx.conf

rm /etc/nginx/nginx.conf.tmp

echo "--- Final Nginx Configuration ---"
cat /etc/nginx/nginx.conf
echo "---------------------------------"

exec nginx -g 'daemon off;'