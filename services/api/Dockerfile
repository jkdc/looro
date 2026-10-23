FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
COPY tests ./tests
COPY migrations ./migrations
COPY operations ./operations
RUN npm run build && cp -R migrations dist/migrations && cp -R operations dist/operations

FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --from=build /app/dist ./dist
COPY --from=build /app/tests/support ./dist/tests/support
COPY migrations ./migrations
USER node
EXPOSE 8080
CMD ["node", "dist/src/main.js"]
