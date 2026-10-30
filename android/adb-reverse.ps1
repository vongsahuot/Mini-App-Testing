# Forward device localhost ports to the host (web, portal, bank, partner).
$ErrorActionPreference = "Stop"
foreach ($p in 3000, 8081, 8082, 8083) {
    adb reverse "tcp:$p" "tcp:$p"
}
adb reverse --list
