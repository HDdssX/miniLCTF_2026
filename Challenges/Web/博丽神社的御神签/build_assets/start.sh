#!/bin/sh

set -eu

echo "$FLAG" > "/tmp/therealflag_$(echo $FLAG | sha3sum -a 512 | head -c 32)"
unset FLAG

su postgres -c "pg_ctl start -D /var/lib/postgresql/data"
sleep 2

echo "Starting PostgREST..."
/usr/local/bin/postgrest /app/postgrest.conf &

echo "Starting Flask web application..."
cd /app
exec python3 app.py
