FROM node:20-alpine AS frontend-build
WORKDIR /build
COPY vue-test-app/package*.json ./
RUN npm ci
COPY vue-test-app/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=frontend-build /build/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/templates/default.conf.template
