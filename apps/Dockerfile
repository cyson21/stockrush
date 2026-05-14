FROM node:24-alpine AS build

ARG APP_DIR
WORKDIR /workspace

COPY apps/${APP_DIR}/package.json apps/${APP_DIR}/package-lock.json ./
RUN npm ci

COPY apps/${APP_DIR}/ ./
RUN npm run build

FROM nginx:1.27-alpine

COPY apps/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 8080
