#!/bin/bash
set -e

docker login -u robinshen -p $@
buildVersion=$(mvn org.apache.maven.plugins:maven-help-plugin:3.2.0:evaluate -Dexpression=project.version -q -DforceStdout)
mvn clean package

BUILDER="builder-$(date +%s)"

function finish {
  docker buildx stop $BUILDER
  docker buildx rm $BUILDER
}
trap finish EXIT

docker buildx create --name $BUILDER
docker buildx use $BUILDER

docker buildx build --push --platform linux/amd64,linux/arm64 -f ./Dockerfile -t 1dev/k8s-helper-linux:$buildVersion .
