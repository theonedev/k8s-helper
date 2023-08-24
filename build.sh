#!/bin/bash
set -e

buildVersion=`ls target/k8s-helper-*-sources.jar|sed -e 's/target\/k8s-helper-\(.*\)-sources.jar/\1/'`

BUILDER="builder-$(date +%s)"

function finish {
  docker buildx stop $BUILDER
  docker buildx rm $BUILDER
}
trap finish EXIT

docker buildx create --name $BUILDER
docker buildx use $BUILDER

docker buildx build --push --platform linux/amd64,linux/arm64 -f ./Dockerfile -t 1dev/k8s-helper-linux:$buildVersion .
