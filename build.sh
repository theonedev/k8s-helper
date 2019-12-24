#!/bin/bash
set -e
if [ $# -eq 0 ]
  then
    echo "Docker hub password should be supplied as argument"
    exit 1
fi
buildVersion=$(mvn org.apache.maven.plugins:maven-help-plugin:3.1.0:evaluate -Dexpression=project.version -q -DforceStdout)
mvn clean package
docker build -t 1dev/k8s-helper-linux:$buildVersion -f Dockerfile.linux .
docker login -u robinshen -p $@
docker push 1dev/k8s-helper-linux:$buildVersion
