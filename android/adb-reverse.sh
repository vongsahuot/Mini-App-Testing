#!/usr/bin/env sh
# Forward device localhost ports to the host (web, portal, bank, partner).
set -e
for p in 3000 8081 8082 8083; do
  adb reverse tcp:$p tcp:$p
done
adb reverse --list
