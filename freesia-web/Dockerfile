# syntax=docker/dockerfile:1
FROM node:24-alpine AS build
WORKDIR /app
ENV HUSKY=0
RUN npm install --global pnpm@11.8.0
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/pnpm/store pnpm install --frozen-lockfile --store-dir /pnpm/store
COPY . .
# 两种部署均使用正式静态构建，同源 API 不包含环境地址或任何后端凭据。
ENV VITE_API_URL="" VITE_BASE_URL="/"
RUN pnpm build

FROM nginx:stable-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/
EXPOSE 80
