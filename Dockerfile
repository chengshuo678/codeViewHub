# 多阶段构建：Maven 编译 → JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/code-review-hub.jar app.jar

# H2 数据库与日志持久化目录
VOLUME ["/app/data"]
EXPOSE 8080

# 并发评审线程数可通过环境变量 REVIEW_WORKERS 覆盖
ENV REVIEW_WORKERS=2

ENTRYPOINT ["java", "-jar", "app.jar"]
