#!/system/bin/sh
# TinyDisplay rear-touch bridge for Ulefone Armor 29 Pro.
# Runs as adb root/shell, reads /dev/input/event5 (hyn_ts 340x340) and sends
# tap/swipe actions to com.tinydisplay.TinyDisplayService.

DEV=/dev/input/event5
PKG=com.tinydisplay
SVC=com.tinydisplay/.TinyDisplayService
LOGTAG=TinyTouchDaemon

log -t "$LOGTAG" "starting on $DEV"

sx=-1; sy=-1; x=-1; y=-1; touching=0

timeout 2147483647 getevent -lt "$DEV" 2>/dev/null | while read line; do
  case "$line" in
    *ABS_MT_POSITION_X*)
      hex=${line##* }
      x=$((16#$hex))
      if [ "$touching" = 1 ] && [ "$sx" = -1 ]; then sx=$x; sy=$y; fi
      ;;
    *ABS_MT_POSITION_Y*)
      hex=${line##* }
      y=$((16#$hex))
      if [ "$touching" = 1 ] && [ "$sx" = -1 ]; then sx=$x; sy=$y; fi
      ;;
    *ABS_MT_TRACKING_ID*)
      hex=${line##* }
      val=$((16#$hex))
      # getevent prints ffffffff for release; shell arithmetic makes it positive, so match text too.
      if echo "$line" | grep -q "ffffffff"; then
        if [ "$touching" = 1 ] && [ "$sx" -ge 0 ] && [ "$sy" -ge 0 ] && [ "$x" -ge 0 ] && [ "$y" -ge 0 ]; then
          dx=$((x-sx)); dy=$((y-sy)); d2=$((dx*dx + dy*dy))
          if [ "$d2" -lt 900 ]; then
            log -t "$LOGTAG" "tap $x,$y"
            am start-foreground-service -n "$SVC" -a com.tinydisplay.action.REAR_TAP --ei x "$x" --ei y "$y" >/dev/null 2>&1
          else
            log -t "$LOGTAG" "swipe $sx,$sy -> $x,$y"
            am start-foreground-service -n "$SVC" -a com.tinydisplay.action.REAR_SWIPE --ei sx "$sx" --ei sy "$sy" --ei ex "$x" --ei ey "$y" >/dev/null 2>&1
          fi
        fi
        touching=0; sx=-1; sy=-1
      else
        touching=1; sx=$x; sy=$y
      fi
      ;;
  esac
done
