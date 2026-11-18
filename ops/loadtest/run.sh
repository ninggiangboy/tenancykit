#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/../../backend/services/billing"
exec ./gradlew :apps:app-boot:loadTest
