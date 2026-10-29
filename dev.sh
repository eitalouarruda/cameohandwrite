#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
exec ./desktop/run.sh "$@"
