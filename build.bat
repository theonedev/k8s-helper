@echo off 
cmd /c mvn org.apache.maven.plugins:maven-help-plugin:3.1.0:evaluate -Dexpression=project.version -q -DforceStdout > version.txt || cat version.txt && rm version.txt && exit /b 1
set /p buildVersion=<version.txt
rm version.txt

cmd /c mvn clean package || exit /b 1

for %%i in (1607,1709,1803,1809,1903,1909,2004,20H2) do (
  for /f %%j in ('make-lowercase %%i') do (
    docker build -t 1dev/k8s-helper-windows-%%j:%buildVersion% -f Dockerfile.windows --build-arg osVersion=%%i . || exit /b 1
    docker push 1dev/k8s-helper-windows-%%j:%buildVersion% || exit /b 1
  )  
)
