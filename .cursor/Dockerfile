FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/opt/jdk-25 \
    MAVEN_HOME=/opt/maven \
    PATH=/opt/jdk-25/bin:/opt/maven/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git tar gzip python3 \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /tmp/jdk.tgz "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz" \
    && echo "dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e  /tmp/jdk.tgz" | sha256sum -c \
    && mkdir -p /opt/jdk-25 \
    && tar -xzf /tmp/jdk.tgz -C /opt/jdk-25 --strip-components=1 \
    && rm /tmp/jdk.tgz \
    && curl -fsSL -o /tmp/maven.tgz "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz" \
    && tar -xzf /tmp/maven.tgz -C /opt \
    && ln -s /opt/apache-maven-3.9.11 /opt/maven \
    && rm /tmp/maven.tgz \
    && ln -sf /opt/jdk-25/bin/java /usr/local/bin/java \
    && ln -sf /opt/jdk-25/bin/javac /usr/local/bin/javac \
    && ln -sf /opt/maven/bin/mvn /usr/local/bin/mvn

USER ubuntu
