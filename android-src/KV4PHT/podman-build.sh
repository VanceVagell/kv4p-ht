#!/bin/bash

podman build .  --format docker -t kv4pht-build:latest
apks=$(podman run --name kv4pht-build kv4pht-build:latest)

mkdir build 2>/dev/null
echo
IFS=$'\n'
for apk in ${apks[@]}; do
    echo "${apk} > build/$(basename $apk)"
    podman cp kv4pht-build:"${apk}" "build/$(basename $apk)"
done
echo
echo "Cleaning up.."
podman rm kv4pht-build
echo "Done"
