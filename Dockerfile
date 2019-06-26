FROM openjdk:8-jre-alpine
RUN apk update && apk upgrade && apk add --no-cache git
COPY target/*.jar /k8s-helper/
COPY target/lib/*.jar /k8s-helper/
