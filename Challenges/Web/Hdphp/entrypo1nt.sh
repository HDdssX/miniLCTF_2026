#!/bin/sh
set -e

echo $FLAG > /flag
unset FLAG

php-fpm -D
exec nginx -g "daemon off;"
