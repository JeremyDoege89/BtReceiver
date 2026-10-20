#!/system/bin/sh
# BT Receiver: Bluetooth speaker (A2DP sink) / normal mode switcher.
# Bundled in the app and installed to /data/adb/btreceiver; works with KernelSU, Magisk and APatch.
# Based on the "btspeaker" module recipe:
#  - the stack can't run A2DP source and sink together, so speaker mode turns source off;
#  - Bluetooth on/off doesn't re-read the profile props, the Bluetooth process has to be killed;
#  - class of device 0x240414 (loudspeaker) so PCs list the phone as an audio device;
#  - a source must pair while the phone is already in speaker mode;
#  - A2DP hardware offload (common on Qualcomm) is turned off, since it only covers the source path.
VERSION=1
DIR=/data/adb/btreceiver
STATE=$DIR/mode
ORIG=$DIR/orig.props
HOOK=/data/adb/post-fs-data.d/btreceiver.sh
PROPS="bluetooth.profile.a2dp.source.enabled bluetooth.profile.avrcp.target.enabled bluetooth.profile.a2dp.sink.enabled bluetooth.profile.avrcp.controller.enabled bluetooth.device.class_of_device persist.bluetooth.a2dp_offload.disabled"

R=
for r in /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop /data/adb/magisk/resetprop $(command -v resetprop); do
  [ -x "$r" ] && { R=$r; break; }
done

setp() { if [ -n "$R" ]; then $R "$1" "$2"; else setprop "$1" "$2"; fi; }
delp() { if [ -n "$R" ]; then $R -d "$1"; else setprop "$1" ""; fi; }

# The stock values, saved once before the first switch so normal mode can put them back exactly.
# Props added in a later version are appended, so an older orig.props still covers everything.
save_orig() {
  # Already switched by something else (e.g. the old btspeaker module): these aren't stock values.
  [ "$(current)" = speaker ] && return
  mkdir -p "$DIR"
  for p in $PROPS; do
    grep -q "^$p=" "$ORIG" 2>/dev/null || echo "$p=$(getprop $p)" >> "$ORIG"
  done
}

apply_speaker() {
  save_orig
  setp bluetooth.profile.a2dp.source.enabled false
  setp bluetooth.profile.avrcp.target.enabled false
  setp bluetooth.profile.a2dp.sink.enabled true
  setp bluetooth.profile.avrcp.controller.enabled true
  setp bluetooth.device.class_of_device 36,4,20
  # In memory only (no -p), so a reboot without the boot hook leaves the stock value.
  setp persist.bluetooth.a2dp_offload.disabled true
}

apply_normal() {
  if [ -f "$ORIG" ]; then
    while IFS='=' read -r p v; do
      if [ -n "$v" ]; then setp "$p" "$v"; else delp "$p"; fi
    done < "$ORIG"
  else
    setp bluetooth.profile.a2dp.source.enabled true
    setp bluetooth.profile.avrcp.target.enabled true
    delp bluetooth.profile.a2dp.sink.enabled
    delp bluetooth.profile.avrcp.controller.enabled
    delp bluetooth.device.class_of_device
  fi
}

current() {
  [ "$(getprop bluetooth.profile.a2dp.sink.enabled)" = true ] && echo speaker || echo normal
}

bt_pids() { pidof com.android.bluetooth com.google.android.bluetooth; }

restart_bt() {
  was_on=$(settings get global bluetooth_on)
  cmd bluetooth_manager disable >/dev/null 2>&1
  i=0; while [ $i -lt 10 ] && [ "$(settings get global bluetooth_on)" != 0 ]; do sleep 1; i=$((i+1)); done
  pids=$(bt_pids)
  [ -n "$pids" ] && kill $pids
  sleep 2
  if [ "$was_on" != 0 ]; then
    cmd bluetooth_manager enable >/dev/null 2>&1
    cmd bluetooth_manager wait-for-state:STATE_ON >/dev/null 2>&1
  fi
}

set_mode() {
  mkdir -p "$DIR"
  echo "$1" > "$STATE"
  if [ "$1" = speaker ]; then apply_speaker; else apply_normal; fi
  restart_bt
  echo "Bluetooth is now in $1 mode."
  return 0
}

# Reapplies speaker mode at boot, before Bluetooth starts.
install_hook() {
  mkdir -p "${HOOK%/*}"
  cat > "$HOOK" <<EOF
#!/system/bin/sh
# Installed by BT Receiver (dev.dreamz.btreceiver).
[ -f $DIR/btmode.sh ] && sh $DIR/btmode.sh boot
EOF
  chmod 755 "$HOOK"
}

# The older standalone "btspeaker" module does the same job; two boot hooks would fight.
retire_legacy() {
  L=/data/adb/modules/btspeaker
  [ -d "$L" ] || return
  if [ ! -f "$STATE" ] && [ -f /data/adb/btspeaker/mode ]; then mkdir -p "$DIR"; cp /data/adb/btspeaker/mode "$STATE"; fi
  [ -f "$L/disable" ] || { touch "$L/disable"; echo "Disabled the old btspeaker module (BT Receiver replaces it)."; }
}

soc() {
  seen=
  for p in ro.soc.manufacturer ro.soc.model ro.board.platform ro.hardware; do
    v=$(getprop $p)
    v=$(echo "$v" | tr 'A-Z' 'a-z')
    [ -z "$v" ] && continue
    case " $seen " in *" $v "*) continue ;; esac
    seen="$seen $v"
  done
  echo $seen
}

root_manager() {
  if [ -d /data/adb/ksu ]; then echo KernelSU
  elif [ -d /data/adb/ap ]; then echo APatch
  elif [ -d /data/adb/magisk ]; then echo Magisk
  else echo unknown; fi
}

case "$1" in
  speaker|normal) set_mode "$1" ;;
  toggle|"")
    if [ "$(current)" = speaker ]; then set_mode normal; else set_mode speaker; fi ;;
  setup)
    install_hook
    retire_legacy
    echo "Installed v$VERSION." ;;
  status)
    echo "Mode: $(current)"
    echo "Saved for next boot: $(cat $STATE 2>/dev/null || echo normal)"
    echo "Bluetooth on: $([ "$(settings get global bluetooth_on)" != 0 ] && echo yes || echo no)"
    echo "SDK: $(getprop ro.build.version.sdk)"
    echo "SoC: $(soc)"
    echo "Root: $(root_manager)"
    echo "resetprop: ${R:-none}"
    echo "Sink service: $(dumpsys bluetooth_manager | grep '^Profile: A2dpSinkService' >/dev/null && echo running || echo stopped)"
    # The sink state machine line carries the source's name, e.g.
    # "mDevice: XX:XX:XX:XX:12:34(MY-PC) name=A2dpSinkStateMachine state=Connected".
    dumpsys bluetooth_manager | grep -A20 '^Profile: A2dpSinkService' | grep -m1 'state=Connected' ;;
  boot)
    [ "$(cat $STATE 2>/dev/null)" = speaker ] && apply_speaker ;;
  uninstall)
    [ "$(current)" = speaker ] && set_mode normal
    rm -f "$HOOK"
    rm -rf "$DIR"
    echo "Removed. Bluetooth is back to normal." ;;
  *)
    echo "Usage: btmode.sh [speaker|normal|toggle|status|setup|uninstall]"
    exit 1 ;;
esac
exit 0
