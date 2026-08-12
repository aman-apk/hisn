#!/usr/bin/env bash

export PATH="$(dirname $0)/usr/bin:${PATH}"

if [ "$1" == "cli" ] || [ "$(basename "$ARGV0")" == "hisn-cli" ] || [ "$(basename "$ARGV0")" == "hisn-cli.AppImage" ]; then
    [ "$1" == "cli" ] && shift
    exec hisn-cli "$@"
elif [ "$1" == "proxy" ] || [ "$(basename "$ARGV0")" == "hisn-proxy" ] || [ "$(basename "$ARGV0")" == "hisn-proxy.AppImage" ]; then
    [ "$1" == "proxy" ] && shift
    exec hisn-proxy "$@"
elif [ -v CHROME_WRAPPER ] || [ -v MOZ_LAUNCHED_CHILD ] || [ "$2" == "keepassxc-browser@keepassxc.org" ]; then
    exec hisn-proxy "$@"
else
    # --- Icon file setup ---
    icon_source="$APPDIR/usr/share/icons/hicolor/256x256/apps/hisn.png"
    icon_target="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/256x256/apps/hisn.png"
    mkdir -p "$(dirname "$icon_target")"

    # Copy icon if different or missing
    if [ ! -f "$icon_target" ] || ! cmp -s "$icon_source" "$icon_target"; then
        echo "Installing Hisn icon to ${icon_target}"
        cp "$icon_source" "$icon_target"
    fi

    # --- Desktop file setup ---
    desktop_source="$APPDIR/usr/share/applications/org.hisn.Hisn.desktop"
    desktop_target="${XDG_DATA_HOME:-$HOME/.local/share}/applications/org.hisn.Hisn.desktop"
    mkdir -p "$(dirname "$desktop_target")"

    # Substitute Exec and TryExec in memory
    desktop_content=$(sed "s|Exec=hisn %f|Exec=$APPIMAGE %f|;s|TryExec=hisn|TryExec=$APPIMAGE|" "$desktop_source")

    # Copy desktop file if different or missing
    if [ ! -f "$desktop_target" ] || ! cmp -s - "$desktop_target" <<<"$desktop_content"; then
        echo "Installing Hisn desktop file to ${desktop_target}"
        printf '%s\n' "$desktop_content" >"$desktop_target"

        if command -v update-desktop-database &>/dev/null; then
            echo "Updating desktop database"
            update-desktop-database "${XDG_DATA_HOME:-$HOME/.local/share}/applications"
        fi
    fi

    EXEC="exec"
    if command -v systemd-run &>/dev/null; then
        EXEC="exec systemd-run --user --scope --slice=app.slice --unit=app-org.hisn.Hisn-$(cat /proc/sys/kernel/random/uuid | tr -d -).scope"
    fi

    $EXEC hisn "$@"
fi
