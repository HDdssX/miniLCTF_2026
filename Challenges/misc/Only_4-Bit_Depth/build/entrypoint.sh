#!/bin/sh
set -eu

python /app/build/init_flag.py
unset FLAG

exec python /app/app.py
