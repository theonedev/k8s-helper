#!/bin/bash
set -e
buildVersion=$(mvn org.apache.maven.plugins:maven-help-plugin:3.1.0:evaluate -Dexpression=project.version -q -DforceStdout)
mvn clean package
docker build -t 1dev/k8s-helper-linux:$buildVersion -f Dockerfile.linux .
docker login -u robinshen -p $@
docker push 1dev/k8s-helper-linux:$buildVersion
