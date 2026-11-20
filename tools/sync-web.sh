#!/bin/sh
# Holt die aktuelle Web-App aus kasparkk/JBL-grip-light-control und haengt die zwei Android-Skripte ein.
set -e
D=$(dirname "$0")/../app/src/main/assets/web
T=$(mktemp -d)
git clone -q --depth 1 https://github.com/kasparkk/JBL-grip-light-control.git "$T/web"
cp "$T/web/icon.svg" "$T/web/manifest.json" "$D/"
python3 - "$T/web/index.html" "$D/index.html" <<'PY'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
s = s.replace("<title>", '<script src="bridge.js"></script>\n<title>', 1)
s = s.replace("</body>", '<script src="android.js"></script>\n</body>', 1)
open(sys.argv[2], "w", encoding="utf-8").write(s)
PY
rm -rf "$T"
echo "Web-App aktualisiert"
