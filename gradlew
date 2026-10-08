#!/bin/sh
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "Install Gradle or use Android Studio to sync/build this project." >&2
exit 1
