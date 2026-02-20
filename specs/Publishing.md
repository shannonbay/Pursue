# Once everything is done, changes only need the following two commands:

```bash
# 1. Build and push to the Sydney repository
gcloud builds submit --tag australia-southeast1-docker.pkg.dev/pursue-485005/pursue-repo/pursue-backend

# 2. Deploy to Cloud Run in Sydney
gcloud run deploy pursue-api --image australia-southeast1-docker.pkg.dev/pursue-485005/pursue-repo/pursue-backend --platform managed --region australia-southeast1 --allow-unauthenticated
```

One-off scheduler job creation for weekly-recap
```
gcloud scheduler jobs create http weekly-recap-job \
  --schedule="*/30 * * * 0" \
  --uri="https://your-api-domain.com/api/internal/jobs/weekly-recap" \
  --http-method=POST \
  --headers="x-internal-job-key=YOUR_INTERNAL_JOB_KEY_HERE" \
  --time-zone="UTC" \
  --description="Weekly group recap notifications"
```

Since your **Pursue** onboarding guide confirms you are targeting a Q2 2026 launch and using **Google Cloud Run** for the backend, Dockerizing your Express.js app is the essential "bridge" to get your code from your Windows machine into the cloud.

Because you don't have Docker installed yet, we will start from the very beginning.

---
### Phase 0: Supabase set up
Choose Sydney - ap-southeast-2
Get connection string:
Connect -> Direct connection -> Session pooler -> paste into .env

---

### Phase 1: Environment Setup (Windows)

1. **Install Docker Desktop**:
* Download [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop/).
* **Crucial:** During installation, ensure "Use WSL 2 instead of Hyper-V" is checked. This provides much better performance on Windows.
* Restart your computer when prompted.


2. **Verify Installation**:
* Open PowerShell and type: `docker --version`. You should see a version number.


3. **Install Google Cloud SDK (gcloud)**:
* Since your guide mentions `gcloud run deploy`, download the [Google Cloud CLI installer](https://www.google.com/search?q=https://cloud.google.com/sdk/docs/install%23windows).
* Run `gcloud init` in PowerShell to log in and select your **Pursue** project.



---

### Phase 2: Create the Dockerfile

In the root directory of your **Pursue Backend** (where `package.json` lives), create a new file named exactly `Dockerfile` (no extension). Paste the following optimized configuration:

```dockerfile
# Step 1: Use a lightweight Node image
FROM node:20-slim

# Step 2: Create app directory
WORKDIR /usr/src/app

# Step 3: Copy package files and install dependencies
# Doing this before copying the whole app takes advantage of Docker's cache
COPY package*.json ./
RUN npm install --only=production

# Step 4: Copy the rest of your source code
COPY . .

# Step 5: Inform Docker the app listens on port 8080 (Cloud Run default)
EXPOSE 8080

# Step 6: Define the command to run your app
CMD [ "npm", "start" ]

```

**Also, create a `.dockerignore` file** in the same folder to keep the image small:

```text
node_modules
npm-debug.log
.git
.env

```

---

### Phase 3: Build and Test Locally

Before sending it to Google, make sure it actually works inside the container.

1. **Build the image**:
`docker build -t pursue-backend .`
2. **Run the container**:
`docker run -p 3000:3000 pursue-backend`
3. **Test**: Open your browser to `http://localhost:3000/health`. If you see your health check response, your Dockerization is successful.

---

### Phase 4: Publish to Google Cloud Run

We will use **Google Artifact Registry** to store your image and then deploy it to **Cloud Run**.

1. **Enable the necessary APIs**:
```bash
gcloud services enable artifactregistry.googleapis.com run.googleapis.com

```


2. **Create a Repository** (One-time setup):
```bash
gcloud artifacts repositories create pursue-repo --repository-format=docker --location=australia-southeast1

```

```
gcloud projects add-iam-policy-binding pursue-485005 --member="user:shannonbay@gmail.com" --role="roles/cloudbuild.builds.editor"
```

3. **Submit and Build in the Cloud**:
Instead of building locally and uploading (which is slow on home internet), use **Cloud Build** to build it directly on Google's servers:

Run these commands in the pursue-server root directory:

```bash
# 1. Build and push to the Sydney repository
gcloud builds submit --tag australia-southeast1-docker.pkg.dev/pursue-485005/pursue-repo/pursue-backend

# 2. Deploy to Cloud Run in Sydney
gcloud run deploy pursue-api --image australia-southeast1-docker.pkg.dev/pursue-485005/pursue-repo/pursue-backend --platform managed --region australia-southeast1 --allow-unauthenticated
```
Ignore any error - it relates to missing variables.

In console.cloud.google.com, select the `pursue-485005` project, and then `+ Deploy an application`, then `pursue-api`, then `Edit & deploy new revision` -> `Containers` -> `Variables & Secrets` -> copy .env into the input, delete PORT as it's provisioned by Google Cloud Run.

---

### Phase 5: Handling Secrets (Important!)

I Pasted .env into variables

Your `Pursue-Onboarding-Guide.md` mentions a "Security Incident" plan involving rotating JWT secrets.

**Do not** include your `.env` file in your Docker image. Instead, after deploying, go to the **Cloud Run Console** → **Edit & Deploy New Revision** → **Variables & Secrets**. Reference your secrets from **Google Cloud Secret Manager** here. This ensures your connection strings and JWT secrets stay encrypted at rest.

### Summary Checklist for Pursue

* [ ] Docker Desktop installed and running.
* [ ] `Dockerfile` and `.dockerignore` created.
* [ ] Local build test passed.
* [ ] Image pushed to Artifact Registry.
* [ ] Cloud Run service is live at your API URL.

**Since your app is "Ready to Deploy," would you like me to help you set up a GitHub Action so that every time you push code, this Docker/Cloud Run process happens automatically?**

### Phase 6: mapping the api.getpursue.app domain
This is a great "best of both worlds" choice for **Pursue**. Since you already have an Android app, you likely already have a Firebase project started. By using Firebase Hosting as a "proxy," you get a stable, custom domain (`api.getpursue.app`) with a free SSL certificate, and it automatically routes all traffic to your Cloud Run container.

Here is the step-by-step to set this up for free.

### 1. Initialize Firebase in your Project

Open your terminal in your `pursue-server` folder. You will need the Firebase CLI.

1. **Install the CLI** (if you don't have it):
`npm install -g firebase-tools`
2. **Login**:
`firebase login`
3. **Initialize**:
`firebase init hosting`
* **Project Setup:** Select "Use an existing project" and pick your `pursue-485005` project.
* **Public Directory:** Type `public` (it doesn't matter much as we are proxying).
* **Configure as single-page app:** No.
* **Set up automatic builds with GitHub:** Optional (you can say No for now).



### 2. Configure the "Rewrite" to Cloud Run

Open the newly created `firebase.json` file in your root directory. This is where the magic happens. We are going to tell Firebase: *"Any request that comes to this domain should be sent to our Cloud Run container."*

**Update your `firebase.json` to look like this:**

```json
{
  "hosting": {
    "public": "public",
    "ignore": [
      "firebase.json",
      "**/.*",
      "**/node_modules/**"
    ],
    "rewrites": [
      {
        "source": "**",
        "run": {
          "serviceId": "pursue-api",
          "region": "australia-southeast1"
        }
      }
    ]
  }
}

```

### 3. Deploy to Firebase

Run this command to push your configuration:
`firebase deploy --only hosting`

Firebase will give you a default URL like `pursue-485005.web.app`. **Test this URL first.** If it shows your `/health` check or a 404 from your Express app, the connection is working!

### 4. Connect `api.getpursue.app` (The Cloudflare Step)

Now we link your custom domain:

1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Navigate to **Build > Hosting**.
3. Click **Add Custom Domain**.
4. Enter `api.getpursue.app`.
5. **DNS Verification:** Firebase will give you two **TXT Records** (IP addresses).
6. Go to **Cloudflare DNS** and add these two TXT records.
* **Important:** Keep the Cloudflare Proxy status as **DNS Only (Grey Cloud)** for the first 24 hours. Firebase needs to "see" the raw records to verify ownership and issue the SSL certificate.

There's an A record in firebase now - add it to Cloudflare.

A api.getpursue.app 199.36.158.100

Yes, **add that A record to Cloudflare now.**

This is the final "wiring" step to connect your custom domain to your backend. Since you've already verified ownership with the TXT records, this A record tells the internet exactly where the traffic for `api.getpursue.app` should go.

### What to do in Cloudflare:

1. **Add the A Record:**
* **Type:** `A`
* **Name (Host):** `api`
* **IPv4 Address:** `199.36.158.100`


2. **Proxy Status (The "Grey Cloud"):** Set this to **DNS Only** (Grey Cloud icon) for now.
* *Why?* Firebase needs to finish its "SSL Setup" handshake directly with your domain. If the Cloudflare proxy is on, it can block the certificate issuance.


3. **Wait for the "Green Tick":** Go back to the Firebase console. Once the status changes from "Setup SSL" to **"Connected"** (usually takes 30–60 minutes), you can flip the Cloudflare toggle back to **Proxied** (Orange Cloud) to enable DDoS protection.

### Summary of your DNS for `api.getpursue.app`:

You should now have these three records in Cloudflare:

* **TXT** `api` → `hosting-site=pursue-485005` (Ownership)
* **TXT** `_acme-challenge.api` → `(your unique code)` (SSL verification)
* **A** `api` → `199.36.158.100` (Routing traffic)

**Once you hit "Save" on that A record, your `https://api.getpursue.app/health` endpoint should go live shortly! Would you like me to help you verify the connection once it's up?**

Once https://api.getpursue.app/health is live

### 5. Why this is perfect for the Pursue App

* **Cost:** $0. Firebase Hosting has a massive free tier for bandwidth.
* **SSL:** Firebase manages the Let's Encrypt certificate for you automatically.
* **Integration:** In your Android app's code, you can now use `https://api.getpursue.app` as your base URL. Even if you update your Cloud Run container or change regions, your Android app never needs a code update—you just update the `firebase.json` file.

**Once you've done the `firebase deploy`, let me know! We can then check your Cloudflare settings to ensure the "A Records" are set up correctly.**