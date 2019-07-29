#!/bin/bash
mvn clean package
docker build -t 1dev/k8s-helper:latest .
docker push 1dev/k8s-helper:latest
