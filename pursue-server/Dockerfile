# Stage 1: Build
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Stage 2: Run
# Step 1: Use a lightweight Node image
FROM node:20-slim

# Step 2: Create app directory
WORKDIR /app

# Step 3: Copy package files and install dependencies
# Doing this before copying the whole app takes advantage of Docker's cache
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/package*.json ./
RUN npm install --only=production

# Step 4: Copy the rest of your source code
COPY . .

# Step 5: Inform Docker the app listens on port 8080 (Cloud Run default)
EXPOSE 8080

# Step 6: Define the command to run your app
CMD [ "npm", "start" ]