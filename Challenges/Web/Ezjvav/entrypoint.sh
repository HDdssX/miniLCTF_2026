#!/bin/sh
set -eu

SECRET_VALUE=${SECRET:-${FLAG:-"ezjvav_dev_placeholder"}}
SECRET_FILE=/dev/shm/ezjvav.secret
RELAY_SEED_FILE=/dev/shm/ezjvav.relayseed
WEB_RELAY_NOTE_FILE=/dev/shm/ezjvav.relaynote
WEB_SEED_FILE=/dev/shm/ezjvav.webseed
BUNDLE_TICKET_FILE=/dev/shm/ezjvav.ticketsecret
WEB_BUNDLE_TICKET_FILE=/dev/shm/ezjvav.ticketsecret.web
BUNDLE_PORT=24631
WEB_SEED_SECRET=$(od -An -N 16 -tx1 /dev/urandom | tr -d ' \n')
RELAY_SEED_SECRET=$(od -An -N 16 -tx1 /dev/urandom | tr -d ' \n')
BUNDLE_TICKET_SECRET=$(od -An -N 24 -tx1 /dev/urandom | tr -d ' \n')
DECOY_SECRET=$(od -An -N 16 -tx1 /dev/urandom | tr -d ' \n')

rm -f "$SECRET_FILE"
printf '%s' "$SECRET_VALUE" > "$SECRET_FILE"
chown bundle:bundle "$SECRET_FILE"
chmod 600 "$SECRET_FILE"

rm -f "$RELAY_SEED_FILE" "$WEB_RELAY_NOTE_FILE" "$WEB_SEED_FILE" "$BUNDLE_TICKET_FILE" "$WEB_BUNDLE_TICKET_FILE"
printf '%s' "$RELAY_SEED_SECRET" > "$RELAY_SEED_FILE"
printf '%s' "$RELAY_SEED_SECRET" > "$WEB_RELAY_NOTE_FILE"
printf '%s' "$WEB_SEED_SECRET" > "$WEB_SEED_FILE"
printf '%s' "$BUNDLE_TICKET_SECRET" > "$BUNDLE_TICKET_FILE"
printf '%s' "$BUNDLE_TICKET_SECRET" > "$WEB_BUNDLE_TICKET_FILE"
chown bundle:bundle "$RELAY_SEED_FILE"
chown web:web "$WEB_RELAY_NOTE_FILE"
chown web:web "$WEB_SEED_FILE"
chown bundle:bundle "$BUNDLE_TICKET_FILE"
chown web:web "$WEB_BUNDLE_TICKET_FILE"
chmod 600 "$RELAY_SEED_FILE"
chmod 600 "$WEB_RELAY_NOTE_FILE"
chmod 600 "$WEB_SEED_FILE"
chmod 600 "$BUNDLE_TICKET_FILE"
chmod 600 "$WEB_BUNDLE_TICKET_FILE"

unset FLAG
unset SECRET
export FLAG=
export SECRET=

mkdir -p /opt/ghost/run/bundle /opt/ghost/run/web /opt/ghost/data/themes
chown bundle:bundle /opt/ghost/run/bundle
chown web:web /opt/ghost/run/web /opt/ghost/data/themes

if [ -f /opt/ghost/bridge/helper-runtime.env ]; then
  # shellcheck disable=SC1091
  . /opt/ghost/bridge/helper-runtime.env
fi

su -s /bin/sh bundle -c "/opt/java/openjdk/bin/java -XX:+DisableAttachMechanism -cp /opt/ghost/bundle/catalog-main.jar ctf.ghostvalve.vault.StoreMain $SECRET_FILE $RELAY_SEED_FILE $BUNDLE_TICKET_FILE $BUNDLE_PORT >/opt/ghost/run/bundle/service.log 2>&1 &"

CATALINA_OPTS="-XX:+DisableAttachMechanism -Dghost.theme.root=/opt/ghost/data/themes -Dezjvav.worker.jar=/opt/ghost/worker/preview-runner.jar -Dezjvav.protocol.jar=/opt/ghost/shared/protocol.jar -Dezjvav.bundle.port=$BUNDLE_PORT -Dezjvav.ticket.secret.file=$WEB_BUNDLE_TICKET_FILE -Dezjvav.relay.note.file=$WEB_RELAY_NOTE_FILE -Dezjvav.bridge.secret=$DECOY_SECRET -Dezjvav.helper.bin=/opt/ghost/bridge/ghost-helper -Dezjvav.helper.op.ping=${HELPER_OP_PING:-1} -Dezjvav.helper.op.preview=${HELPER_OP_PREVIEW:-2} -Dezjvav.helper.op.install=${HELPER_OP_INSTALL:-3} -Dezjvav.helper.order.preview=${HELPER_ORDER_PREVIEW:-0,1,2,3,4} -Dezjvav.helper.order.install=${HELPER_ORDER_INSTALL:-0,1,2,3,4,5,6}"
export CATALINA_OPTS

exec su -s /bin/sh web -c "sh /opt/tomcat/bin/catalina.sh run"
