#!/system/bin/sh
# Trigger fake incoming call via Telecom self-managed ConnectionService
# Kali/NetHunter SSH often lacks /system/bin in PATH; am needs cmd on PATH.
export PATH=/system/bin:/system/xbin:${PATH}
NUMBER="${1:-***}"
NAME="${2:-***}"
BRIDGE_HOST="${3:-127.0.0.1}"
BRIDGE_PORT="${4:-18799}"
/system/bin/am broadcast -a com.nova.fakecall.ACTION_RING \
  -n com.nova.fakecall/.RingReceiver \
  --es number "$NUMBER" \
  --es name "$NAME" \
  --es bridge_host "$BRIDGE_HOST" \
  --es bridge_port "$BRIDGE_PORT"
echo "RING sent number=$NUMBER name=$NAME bridge=$BRIDGE_HOST:$BRIDGE_PORT"
