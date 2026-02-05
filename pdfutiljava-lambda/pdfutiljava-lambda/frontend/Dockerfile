FROM nginx:alpine

# Install curl for API Gateway discovery
RUN apk add --no-cache curl sed

# Remove default nginx config
RUN rm -f /etc/nginx/conf.d/default.conf

# Copy nginx config template (API Gateway URL injected at runtime)
RUN mkdir -p /etc/nginx/templates
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# Copy frontend files
COPY index.html /usr/share/nginx/html/

# Copy entrypoint script
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]
