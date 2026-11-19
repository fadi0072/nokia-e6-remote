#!/bin/bash
# Builds the Mac server: dist/e6remote-server
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist
swiftc -O -o dist/e6remote-server mac/E6RemoteServer.swift
echo "Built dist/e6remote-server"
