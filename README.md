# clusterbench-jmeter-client

This project is used to produce a docker image containing JMeter and Java Samplers to be used against 
[clusterbench](https://github.com/clusterbench/clusterbench);

## build

```shell
mvn clean package
```

## run

```shell
mvn clean package
```

## inspect image

```shell
podman run -it --rm --entrypoint sh quay.io/tborgato/clusterbench-jmeter-client:1.0-SNAPSHOT

/opt/apache-jmeter-5.6.3 # ls -ltr lib/
...
-rw-r--r--    1 root     root          7821 Jan  2 15:42 bshclient.jar
drwxr-xr-x    1 root     root            20 Mar 14 10:00 junit
-rw-r--r--    1 root     root        211778 Mar 14 11:23 jmeter-plugins-cmn-jmeter-0.6.jar
drwxr-xr-x    1 root     root           120 Mar 14 11:23 ext
/opt/apache-jmeter-5.6.3 # ls -ltr lib/ext/
...
-rw-r--r--    1 root     root        565558 Jan  2 15:44 ApacheJMeter_http.jar
-rw-r--r--    1 root     root        905838 Mar 14 11:23 jmeter-plugins-manager-1.6.jar
-rw-r--r--    1 root     root         69623 Mar 14 11:23 jmeter-plugins-casutg-2.10.jar
```