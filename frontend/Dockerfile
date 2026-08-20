FROM node:22.18.0-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM nginx:1.29.1-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY ../infra/nginx/default.conf /etc/nginx/conf.d/default.conf
