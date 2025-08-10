# broadcast-frontend/Dockerfile

FROM nginx:alpine3.22

# The Nginx image is based on Alpine Linux. Install 'gettext', which provides the 'envsubst' utility.
RUN apk add --no-cache gettext

# Copy the static UI files
COPY ./dist /usr/share/nginx/html

# Copy the nginx config as a template file
COPY ./nginx.conf /etc/nginx/nginx.conf.template

# Copy the startup script
COPY ./docker-entrypoint.sh /docker-entrypoint.sh

# Make the startup script executable
RUN chmod +x /docker-entrypoint.sh

# Set the startup script as the entrypoint for the container
ENTRYPOINT ["/docker-entrypoint.sh"]

# Expose the HTTPS port
EXPOSE 443