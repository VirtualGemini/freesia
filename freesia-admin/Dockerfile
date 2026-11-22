# syntax=docker/dockerfile:1
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY . .
# 镜像构建负责打包；测试单独执行，避免构建过程要求访问宿主机 Docker socket。
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests clean package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S freesia && adduser -S -D -H -G freesia freesia
WORKDIR /app
COPY --from=build --chown=freesia:freesia /workspace/freesia-server/target/freesia-admin.jar ./freesia-admin.jar
USER freesia
EXPOSE 8080
# 参数可由 compose run freesia-admin bootstrap-admin ... 追加，普通启动不自动创建账号。
ENTRYPOINT ["java", "-jar", "/app/freesia-admin.jar"]
