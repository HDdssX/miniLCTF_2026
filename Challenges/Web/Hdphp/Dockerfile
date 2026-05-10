FROM php:7.4-fpm-alpine
LABEL authors="HDdss"

RUN apk add --no-cache nginx

WORKDIR /var/www/html
RUN rm /etc/nginx/http.d/default.conf
COPY index.php .
COPY nginx.conf /etc/nginx/http.d/master.conf
COPY entrypo1nt.sh /entrypo1nt.sh

RUN sed -i 's/^user nginx;/user www-data;/' /etc/nginx/nginx.conf && \
    chown -R www-data:root /var/lib/nginx /var/log/nginx /run/nginx && \
    chmod -R 755 /var/lib/nginx/tmp

RUN chmod +x /entrypo1nt.sh

EXPOSE 80

ENTRYPOINT ["/entrypo1nt.sh"]
