#!/usr/bin/env bash
set -u

PACKAGE="${1:-com.podly}"

section() {
  printf '\n== %s ==\n' "$1"
}

run() {
  printf '$ %s\n' "$*"
  "$@"
}

section "ADB devices"
run adb devices

section "Installed package"
run adb shell pm list packages "$PACKAGE"

section "Launch activity"
run adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER \
  "$PACKAGE"

section "Media browser services"
run adb shell pm query-services --brief \
  -a android.media.browse.MediaBrowserService \
  "$PACKAGE"

section "Media3 library services"
run adb shell pm query-services --brief \
  -a androidx.media3.session.MediaLibraryService \
  "$PACKAGE"

section "Package state"
INSTALLER_LINE="$(adb shell pm list packages -i "$PACKAGE" | tr -d '\r')"
printf '%s\n' "$INSTALLER_LINE"
if printf '%s\n' "$INSTALLER_LINE" | grep -q 'installer=null'; then
  printf 'WARN: installer=null means this is sideloaded. Android Auto hides sideloaded media apps unless its own Developer settings > Unknown sources toggle is enabled.\n'
fi
run adb shell pm path "$PACKAGE"
printf '$ adb shell dumpsys package %s | sed -n <selected sections>\n' "$PACKAGE"
adb shell dumpsys package "$PACKAGE" |
  sed -n \
    -e '/Service Resolver Table:/,/Domain verification status:/p' \
    -e "/Package \\[$PACKAGE\\]/,/User 0:/p" \
    -e '/User 0:/,/runtime permissions:/p' \
    -e '/queryable via interaction:/,/queryable via uses-library:/p'

section "Recent Android Auto / media logs"
run adb logcat -d -t 300 \
  AndroidAuto:E CarMedia:E CarApp:E MediaBrowserService:E MediaSessionService:E MediaLibraryService:E '*:S'

section "Manual checks"
cat <<EOF
If the package and media service resolve above but Podly is absent from Android Auto:
1. Open Android Auto on the phone.
2. Tap Version repeatedly to enable Developer settings.
3. Open the three-dot menu > Developer settings.
4. Enable Unknown sources.
5. Force stop Android Auto / Google Play services, reconnect, or reboot the phone.
6. In the car launcher, check Customize launcher and the media app picker.

For the Desktop Head Unit, restart the DHU after reinstalling the APK.
EOF
