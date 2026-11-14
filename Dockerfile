# 운영 이미지. CI 가 bootJar 와 build/docker-assets(PDF 폰트, RDS CA truststore)를 준비한 뒤 빌드한다.
FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 easython
COPY build/docker-assets/NotoSansKR-Regular.ttf build/docker-assets/rds-truststore.p12 /etc/easython/
COPY build/libs/easython-0.0.1-SNAPSHOT.jar /app/app.jar
USER easython
ENV SPRING_PROFILES_ACTIVE=prod SERVER_ADDRESS=0.0.0.0 TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["java", "-Xms128m", "-Xmx384m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
