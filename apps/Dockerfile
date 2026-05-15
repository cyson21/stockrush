FROM node:24-alpine AS build

ARG APP_DIR
ARG VITE_AUTH_ISSUER
ARG VITE_AUTH_CLIENT_ID
WORKDIR /workspace

COPY apps/${APP_DIR}/package.json apps/${APP_DIR}/package-lock.json ./
RUN npm ci

COPY apps/${APP_DIR}/ ./
ENV VITE_AUTH_ISSUER=${VITE_AUTH_ISSUER}
ENV VITE_AUTH_CLIENT_ID=${VITE_AUTH_CLIENT_ID}
RUN npm run build

FROM nginx:1.27-alpine

COPY apps/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 8080
