FROM openjdk:8-jdk
COPY target/*.jar /k8s-helper/
COPY target/lib/*.jar /k8s-helper/
