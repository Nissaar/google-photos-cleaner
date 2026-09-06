#!/usr/bin/env bash
#
# Generates the release signing key for Photo Cleaner.
#
# Run this ONCE. The resulting keystore is the app's permanent identity: Android
# only accepts an update if it is signed by the same key as the installed version.
# If you lose it, you can never update the app for anyone who installed it — they
# would have to uninstall first, losing their local data.
#
# Passwords are typed into keytool's own prompts, so they never appear in your
# shell history, in this script, or in any file.
#
# Usage:  ./tools/make-keystore.sh [output.jks] [alias]

set -euo pipefail

OUT="${1:-photo-cleaner-release.jks}"
ALIAS="${2:-photocleaner}"

if [ -e "$OUT" ]; then
    echo "Refusing to overwrite the existing keystore at: $OUT" >&2
    echo "If you really mean to start over, move the old one aside first." >&2
    exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "keytool not found. It ships with the JDK — set JAVA_HOME or add it to PATH." >&2
    exit 1
fi

cat <<'EOF'
You will be asked for:
  * a keystore password (choose a long, unique one — a password manager entry)
  * your name / organisation / location (these end up in the certificate;
    anything truthful is fine, and they cannot be changed later)

Note: PKCS12 keystores use one password for both the store and the key, so the
KEYSTORE_PASSWORD and KEY_PASSWORD secrets below get the same value.

EOF

# 10000 days is about 27 years. The key must outlive every release you will ever
# publish, because a expired key cannot sign updates.
keytool -genkeypair -v \
    -keystore "$OUT" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000

chmod 600 "$OUT"

echo
echo "Created: $OUT (permissions set to 600)"
echo
echo "Fingerprint — keep a copy of this somewhere separate, so you can always"
echo "verify which key an APK was signed with:"
echo
keytool -list -v -keystore "$OUT" -alias "$ALIAS" 2>/dev/null \
    | grep -E "SHA1:|SHA256:|Valid from" || true

cat <<EOF

Next steps
----------

1. BACK IT UP before doing anything else. See "Signing" in the README.
   At minimum: your password manager, plus one offline copy.

2. Add these four repository secrets on GitHub
   (Settings > Secrets and variables > Actions > New repository secret):

     KEYSTORE_BASE64     base64 -w0 "$OUT"   (run it and paste the output)
     KEY_ALIAS           $ALIAS
     KEYSTORE_PASSWORD   the password you just chose
     KEY_PASSWORD        the same password

3. Keep the .jks OUT of git. The repo's .gitignore already excludes *.jks
   and *.keystore, but check before committing:  git status

EOF
