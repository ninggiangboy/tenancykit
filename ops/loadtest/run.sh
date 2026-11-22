#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/../../backend/services/apps/billing"
exec ./gradlew :apps:app-boot:loadTest
