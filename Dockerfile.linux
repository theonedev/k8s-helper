# do not use alpine image here to avoid the issue that git can not be configured to trust specified certificate when connecting to a https url
FROM openjdk:11-jre-slim
RUN apt-get update && apt-get install -y git git-lfs && rm -rf /var/lib/apt/lists/* 
COPY target/*.jar /k8s-helper/
COPY target/lib/*.jar /k8s-helper/
