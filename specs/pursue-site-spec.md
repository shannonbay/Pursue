# Pursue Marketing Site Specification

**Version:** 1.0  
**Last Updated:** January 30, 2026  
**Repository:** `pursue-site`  
**Domain:** getpursue.app  
**Hosting:** Cloudflare Pages  

---

## 1. Overview

### 1.1 Purpose

The Pursue marketing site is the primary web presence for the Pursue mobile app. It serves to:
- Convert visitors into app downloads
- Explain the product value proposition
- Handle invite deep links
- Provide legal documentation (privacy, terms)
- Establish SEO presence for "group goal accountability"

### 1.2 Technology Stack

**Framework:** Astro 4.x (recommended) or plain HTML/CSS/JS  
**Styling:** Tailwind CSS 3.x  
**Hosting:** Cloudflare Pages (free tier)  
**Deployment:** Git-based (auto-deploy on push)  
**Build Time:** < 1 minute  
**Performance Target:** 
- Lighthouse Score: 95+ (all categories)
- First Contentful Paint: < 1s
- Time to Interactive: < 2s

**Why Astro:**
- ✅ Zero JavaScript by default (fast!)
- ✅ Component-based (reusable code)
- ✅ Built-in Tailwind support
- ✅ Markdown support for blog
- ✅ SEO-friendly (static HTML)
- ✅ Easy to learn (uses HTML-like syntax)

**Alternative:** Plain HTML if you prefer simplicity (no build step)

---

## 2. Site Structure

### 2.1 Pages & Routes

```
getpursue.app/
├── /                           ← Landing page (home)
├── /features                   ← Feature details
├── /pricing                    ← Pricing (freemium model)
├── /invite/:code               ← Invite deep link handler
├── /privacy                    ← Privacy policy
├── /terms                      ← Terms of service
├── /support                    ← Support/FAQ
├── /blog/*                     ← Blog posts (optional, SEO)
└── /.well-known/
    └── assetlinks.json         ← Android app verification
```

### 2.2 Repository Structure

```
pursue-site/
├── public/
│   ├── favicon.ico
│   ├── logo.svg
│   ├── logo-with-text.svg
│   ├── app-icon.png
│   ├── screenshots/
│   │   ├── today-screen.png
│   │   ├── goal-card.png
│   │   ├── group-detail.png
│   │   └── progress-chart.png
│   ├── og-image.png            ← Open Graph (social sharing)
│   └── .well-known/
│       └── assetlinks.json     ← Android deep link verification
├── src/
│   ├── components/
│   │   ├── Header.astro
│   │   ├── Footer.astro
│   │   ├── FeatureCard.astro
│   │   ├── AppStoreButtons.astro
│   │   └── Newsletter.astro
│   ├── layouts/
│   │   └── BaseLayout.astro    ← Main layout wrapper
│   └── pages/
│       ├── index.astro         ← Landing page
│       ├── features.astro
│       ├── pricing.astro
│       ├── privacy.astro
│       ├── terms.astro
│       ├── support.astro
│       └── invite/
│           └── [code].astro    ← Dynamic invite handler
├── .gitignore
├── astro.config.mjs
├── package.json
├── tailwind.config.mjs
└── README.md
```

---

## 3. Page Specifications

### 3.1 Landing Page (/)

**Purpose:** Convert visitors to app downloads

**Sections:**

#### **1. Hero Section**

```
┌─────────────────────────────────────────────┐
│  [PURSUE logo]          [Download App]      │ ← Header
├─────────────────────────────────────────────┤
│                                             │
│   📱                                         │
│   Track goals together.                     │ ← Headline
│   Stay accountable.                         │
│                                             │
│   Join accountability groups with friends   │ ← Subheadline
│   and achieve more together.                │
│                                             │
│   [📱 App Store]  [📱 Google Play]          │ ← CTAs
│                                             │
│   ┌────────────────────────────────────┐   │
│   │                                    │   │
│   │   [App Screenshot - Today Screen]  │   │ ← Hero image
│   │                                    │   │
│   └────────────────────────────────────┘   │
│                                             │
└─────────────────────────────────────────────┘
```

**Copy:**
- Headline: "Track goals together. Stay accountable."
- Subheadline: "Join accountability groups with friends and achieve more together."
- CTA: "Download Free App"

**Visual:**
- App screenshot (Today screen showing goals)
- Clean gradient background (blue to white)
- Mobile-first design

#### **2. Features Section**

```
┌─────────────────────────────────────────────┐
│   How Pursue Works                          │ ← Section title
│                                             │
│   ┌───────┐  ┌───────┐  ┌───────┐         │
│   │  🎯   │  │  👥   │  │  📊   │         │ ← Icons
│   │Create │  │Invite │  │Track  │         │
│   │Goals  │  │Friends│  │Progress│         │
│   │       │  │       │  │        │         │
│   │Set    │  │Share  │  │See     │         │
│   │daily, │  │invite │  │who's   │         │
│   │weekly │  │codes  │  │crushing│         │
│   │goals  │  │easily │  │it      │         │
│   └───────┘  └───────┘  └───────┘         │
│                                             │
└─────────────────────────────────────────────┘
```

**Features to highlight:**
1. **Create Goals** - Daily, weekly, monthly habits
2. **Invite Friends** - Share invite codes instantly
3. **Track Together** - See everyone's progress
4. **Stay Motivated** - Push notifications when teammates log

#### **3. Social Proof** (Later, when you have users)

```
┌─────────────────────────────────────────────┐
│   "Since joining Pursue, I've hit my gym    │
│   goal 3x/week for 8 weeks straight!"       │
│   - Alex, Morning Runners group             │
│                                             │
│   Join 1,000+ users achieving their goals   │ ← When you have data
└─────────────────────────────────────────────┘
```

**Initially:** Skip this section, add later when you have testimonials

#### **4. Final CTA**

```
┌─────────────────────────────────────────────┐
│   Ready to pursue your goals?               │
│                                             │
│   [📱 App Store]  [📱 Google Play]          │
│                                             │
│   Free to download. No credit card needed.  │
└─────────────────────────────────────────────┘
```

#### **5. Footer**

```
┌─────────────────────────────────────────────┐
│  PURSUE                                     │
│                                             │
│  Product           Legal         Social     │
│  Features          Privacy       Twitter    │
│  Pricing           Terms         Instagram  │
│  Support           Contact                  │
│                                             │
│  © 2026 Pursue. All rights reserved.        │
└─────────────────────────────────────────────┘
```

---

### 3.2 Features Page (/features)

**Purpose:** Detailed feature explanations

**Sections:**

#### **Goal Types**
- Daily habits (meditate, run)
- Weekly targets (gym 3x, call parents)
- Monthly milestones (read 2 books)
- Yearly goals (run marathon)

#### **Social Accountability**
- See who's crushing their goals
- Push notifications when teammates log
- Group activity feed
- Friendly competition

#### **Progress Tracking**
- Tap to log (instant feedback)
- Progress charts and heatmaps
- Streaks and statistics
- Historical data

#### **Group Management**
- Create unlimited groups (free tier: 1)
- Invite via codes or links
- Admin controls
- Member roles

**Format:** Feature + Screenshot pairs

```
┌─────────────────────────────────────────────┐
│   📊 Track Progress Together                │
│                                             │
│   See real-time updates when your           │
│   accountability crew logs their goals.     │
│   Celebrate wins together.                  │
│                                             │
│   [Screenshot: Group activity feed]         │
└─────────────────────────────────────────────┘
```

---

### 3.3 Pricing Page (/pricing)

**Purpose:** Explain freemium model

**Pricing Tiers:**

```
┌──────────────┐  ┌──────────────┐
│    Free      │  │   Premium    │
│              │  │              │
│  $0/month    │  │  $30/year    │
│              │  │              │
│ ✓ 1 group    │  │ ✓ Unlimited  │
│ ✓ Unlimited  │  │   groups     │
│   members    │  │ ✓ All Free   │
│ ✓ All goal   │  │   features   │
│   types      │  │ ✓ Priority   │
│ ✓ Progress   │  │   support    │
│   tracking   │  │              │
│              │  │              │
│ [Start Free] │  │ [Upgrade]    │
└──────────────┘  └──────────────┘
```

**Copy:**
- "Start free, upgrade when you need more groups"
- "Most users stay on the free plan"
- "No credit card required"

---

### 3.4 Invite Handler (/invite/:code)

**Purpose:** Deep link to app or app store

**Mobile Flow:**

```html
<!-- This is a CRITICAL page for viral growth -->
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Join Pursue Group</title>
  <meta name="description" content="You've been invited to join a Pursue accountability group!">
  
  <!-- Open Graph for sharing -->
  <meta property="og:title" content="Join my Pursue group!">
  <meta property="og:description" content="Track goals together and stay accountable.">
  <meta property="og:image" content="https://getpursue.app/og-image.png">
</head>
<body>
  <div id="app">
    <!-- Shown while redirecting -->
    <div style="text-align: center; padding: 50px;">
      <img src="/logo.svg" alt="Pursue" width="80">
      <h1>🎯 Join Pursue Group</h1>
      <p>Opening app...</p>
      <div class="spinner"></div>
    </div>
  </div>

  <script>
    // Extract invite code from URL
    const pathParts = window.location.pathname.split('/');
    const inviteCode = pathParts[pathParts.length - 1];
    
    // Detect platform
    const userAgent = navigator.userAgent || navigator.vendor || window.opera;
    const isAndroid = /android/i.test(userAgent);
    const isIOS = /iPad|iPhone|iPod/.test(userAgent) && !window.MSStream;
    const isMobile = isAndroid || isIOS;
    
    if (isMobile) {
      // Attempt to open app via deep link
      const appScheme = `pursue://invite/${inviteCode}`;
      window.location.href = appScheme;
      
      // Fallback to app store after 2.5 seconds
      setTimeout(() => {
        if (isAndroid) {
          window.location.href = 'https://play.google.com/store/apps/details?id=app.getpursue';
        } else if (isIOS) {
          window.location.href = 'https://apps.apple.com/app/pursue/idXXXXXXXX';
        }
      }, 2500);
      
    } else {
      // Desktop: Show QR code and instructions
      document.getElementById('app').innerHTML = `
        <div style="max-width: 600px; margin: 100px auto; padding: 40px; text-align: center;">
          <img src="/logo.svg" alt="Pursue" width="100" style="margin-bottom: 20px;">
          <h1 style="font-size: 32px; margin-bottom: 10px;">📱 Open on Mobile</h1>
          <p style="font-size: 18px; color: #666; margin-bottom: 30px;">
            Scan this QR code with your phone to join the group
          </p>
          
          <div style="background: white; padding: 20px; border-radius: 12px; display: inline-block; box-shadow: 0 4px 6px rgba(0,0,0,0.1);">
            <img src="https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(window.location.href)}" 
                 alt="QR Code" 
                 width="200" 
                 height="200">
          </div>
          
          <p style="margin-top: 30px; color: #999;">
            Or visit this link on your mobile device:<br>
            <strong style="color: #1976D2;">getpursue.app/invite/${inviteCode}</strong>
          </p>
          
          <div style="margin-top: 40px;">
            <a href="/" style="display: inline-block; padding: 12px 24px; background: #1976D2; color: white; text-decoration: none; border-radius: 8px; font-weight: 600;">
              Learn More About Pursue
            </a>
          </div>
        </div>
      `;
    }
  </script>
  
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      margin: 0;
      padding: 0;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      min-height: 100vh;
      color: #333;
    }
    
    .spinner {
      border: 4px solid #f3f3f3;
      border-top: 4px solid #1976D2;
      border-radius: 50%;
      width: 40px;
      height: 40px;
      animation: spin 1s linear infinite;
      margin: 20px auto;
    }
    
    @keyframes spin {
      0% { transform: rotate(0deg); }
      100% { transform: rotate(360deg); }
    }
  </style>
</body>
</html>
```

**Desktop Flow:**
1. Show QR code for mobile scanning
2. Display invite code
3. Link to landing page

**Tracking (Optional):**
```javascript
// Track invite clicks (optional analytics)
fetch('https://api.getpursue.app/analytics/invite-click', {
  method: 'POST',
  body: JSON.stringify({ code: inviteCode, platform: 'mobile' })
});
```

---

### 3.5 Privacy Policy (/privacy)

**Purpose:** Legal requirement for app stores

**Sections:**
1. Information We Collect
2. How We Use Information
3. Data Storage and Security
4. Third-Party Services
5. Your Rights
6. Children's Privacy
7. Changes to Policy
8. Contact Information

**Template:** Use privacy policy generator (iubenda.com has free tier)

**Key Points:**
- Email, display name, password (hashed)
- Goal and progress data
- Group membership data
- No selling of data
- GDPR compliant
- Account deletion available

---

### 3.6 Terms of Service (/terms)

**Purpose:** Legal protection

**Sections:**
1. Acceptance of Terms
2. Description of Service
3. User Accounts
4. User Conduct
5. Intellectual Property
6. Termination
7. Disclaimers
8. Limitation of Liability
9. Governing Law

**Template:** Use ToS generator (termsfeed.com)

---

### 3.7 Support Page (/support)

**Purpose:** Self-service help, reduce support emails

**Format:** FAQ

**Questions:**

**Getting Started:**
- How do I create an account?
- How do I join a group?
- What's an invite code?

**Using Pursue:**
- How do I log progress?
- Can I edit or delete progress entries?
- What are the different goal types?
- How do I invite friends?

**Managing Groups:**
- How do I create a group?
- What's the difference between admin and member?
- Can I leave a group?
- How do I delete a group?

**Premium:**
- What's included in Premium?
- How do I upgrade?
- Can I cancel anytime?

**Troubleshooting:**
- App won't sync
- Notifications not working
- Can't join group with invite code

**Contact:**
- Email: support@getpursue.app
- Response time: Within 24 hours

---

## 4. Design System

### 4.1 Colors

**Primary Palette (Colorblind-Friendly):**

```css
/* Tailwind config */
colors: {
  // Blue (primary)
  'pursue-blue': {
    50: '#E3F2FD',
    100: '#BBDEFB',
    500: '#1976D2',  // Primary brand color
    600: '#1565C0',
    700: '#0D47A1',
  },
  
  // Gold (accent)
  'pursue-gold': {
    50: '#FFF8E1',
    100: '#FFECB3',
    500: '#F57C00',  // Accent color
    600: '#E65100',
  },
  
  // Neutrals
  'pursue-gray': {
    50: '#FAFAFA',
    100: '#F5F5F5',
    200: '#EEEEEE',
    500: '#9E9E9E',
    700: '#616161',
    900: '#212121',
  }
}
```

**Usage:**
- Primary CTA: Blue 500
- Hover: Blue 600
- Accent: Gold 500
- Text: Gray 900
- Background: White / Gray 50

### 4.2 Typography

```css
/* Font Family */
font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', sans-serif;

/* Scale */
text-xs: 12px
text-sm: 14px
text-base: 16px
text-lg: 18px
text-xl: 20px
text-2xl: 24px
text-3xl: 30px
text-4xl: 36px
text-5xl: 48px
```

**Headings:**
- H1: text-5xl, font-bold (Landing hero)
- H2: text-4xl, font-bold (Section headers)
- H3: text-2xl, font-semibold (Feature titles)

**Body:**
- Regular: text-base
- Small: text-sm (captions, labels)

### 4.3 Components

#### **Button Styles**

```css
/* Primary Button */
.btn-primary {
  @apply px-6 py-3 bg-pursue-blue-500 text-white rounded-lg font-semibold;
  @apply hover:bg-pursue-blue-600 transition-colors;
  @apply shadow-md hover:shadow-lg;
}

/* Secondary Button */
.btn-secondary {
  @apply px-6 py-3 bg-white text-pursue-blue-500 rounded-lg font-semibold;
  @apply border-2 border-pursue-blue-500;
  @apply hover:bg-pursue-blue-50 transition-colors;
}

/* App Store Button */
.btn-app-store {
  @apply inline-flex items-center gap-2 px-6 py-3 bg-black text-white rounded-lg;
  @apply hover:bg-gray-800 transition-colors;
}
```

#### **Card Style**

```css
.card {
  @apply bg-white rounded-xl shadow-md p-6;
  @apply hover:shadow-lg transition-shadow;
}
```

#### **Feature Card**

```astro
<!-- components/FeatureCard.astro -->
---
export interface Props {
  icon: string;
  title: string;
  description: string;
}

const { icon, title, description } = Astro.props;
---

<div class="card text-center">
  <div class="text-5xl mb-4">{icon}</div>
  <h3 class="text-2xl font-semibold mb-2">{title}</h3>
  <p class="text-pursue-gray-700">{description}</p>
</div>
```

### 4.4 Spacing

```css
/* Consistent spacing scale */
gap-2: 8px
gap-4: 16px
gap-6: 24px
gap-8: 32px
gap-12: 48px
gap-16: 64px

/* Section padding */
py-16: 64px (mobile)
py-24: 96px (desktop)
```

---

## 5. SEO & Meta Tags

### 5.1 Base Layout Meta Tags

```html
<!-- layouts/BaseLayout.astro -->
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  
  <!-- Primary Meta Tags -->
  <title>Pursue - Group Goal Accountability App</title>
  <meta name="title" content="Pursue - Group Goal Accountability App">
  <meta name="description" content="Track goals together with friends. Join accountability groups, log daily progress, and achieve more together. Available on iOS and Android.">
  <meta name="keywords" content="goal tracking, accountability, habit tracker, group goals, social accountability">
  
  <!-- Open Graph / Facebook -->
  <meta property="og:type" content="website">
  <meta property="og:url" content="https://getpursue.app/">
  <meta property="og:title" content="Pursue - Group Goal Accountability App">
  <meta property="og:description" content="Track goals together with friends. Join accountability groups and achieve more together.">
  <meta property="og:image" content="https://getpursue.app/og-image.png">
  
  <!-- Twitter -->
  <meta property="twitter:card" content="summary_large_image">
  <meta property="twitter:url" content="https://getpursue.app/">
  <meta property="twitter:title" content="Pursue - Group Goal Accountability App">
  <meta property="twitter:description" content="Track goals together with friends. Join accountability groups and achieve more together.">
  <meta property="twitter:image" content="https://getpursue.app/og-image.png">
  
  <!-- Favicon -->
  <link rel="icon" type="image/x-icon" href="/favicon.ico">
  <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
  
  <!-- Canonical URL -->
  <link rel="canonical" href="https://getpursue.app/">
</head>
```

### 5.2 Structured Data (JSON-LD)

```html
<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "MobileApplication",
  "name": "Pursue",
  "operatingSystem": "Android, iOS",
  "applicationCategory": "HealthApplication",
  "offers": {
    "@type": "Offer",
    "price": "0",
    "priceCurrency": "USD"
  },
  "aggregateRating": {
    "@type": "AggregateRating",
    "ratingValue": "4.8",
    "ratingCount": "127"
  }
}
</script>
```

### 5.3 Sitemap

```xml
<!-- public/sitemap.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://getpursue.app/</loc>
    <lastmod>2026-01-30</lastmod>
    <priority>1.0</priority>
  </url>
  <url>
    <loc>https://getpursue.app/features</loc>
    <priority>0.8</priority>
  </url>
  <url>
    <loc>https://getpursue.app/pricing</loc>
    <priority>0.8</priority>
  </url>
  <url>
    <loc>https://getpursue.app/privacy</loc>
    <priority>0.5</priority>
  </url>
  <url>
    <loc>https://getpursue.app/terms</loc>
    <priority>0.5</priority>
  </url>
</urlset>
```

### 5.4 robots.txt

```
# public/robots.txt
User-agent: *
Allow: /
Disallow: /invite/

Sitemap: https://getpursue.app/sitemap.xml
```

---

## 6. Performance Optimization

### 6.1 Images

**Requirements:**
- Format: WebP (with PNG/JPEG fallback)
- Max size: 500KB per image
- Lazy loading: All below-fold images
- Responsive: Multiple sizes via srcset

**Example:**
```html
<img 
  src="/screenshots/today-screen.webp"
  srcset="
    /screenshots/today-screen-400.webp 400w,
    /screenshots/today-screen-800.webp 800w,
    /screenshots/today-screen-1200.webp 1200w
  "
  sizes="(max-width: 640px) 400px, (max-width: 1024px) 800px, 1200px"
  alt="Pursue app today screen"
  loading="lazy"
  width="800"
  height="1600"
>
```

### 6.2 Critical CSS

**Inline critical CSS in <head>:**
```html
<style>
  /* Critical above-the-fold styles */
  body { margin: 0; font-family: sans-serif; }
  .hero { min-height: 100vh; }
  /* ... */
</style>
```

**Load full CSS async:**
```html
<link rel="preload" href="/styles.css" as="style" onload="this.onload=null;this.rel='stylesheet'">
```

### 6.3 JavaScript

**Minimize JS:**
- No framework on static pages (just Astro build)
- Inline small scripts (< 1KB)
- Defer non-critical JS

```html
<script defer src="/analytics.js"></script>
```

### 6.4 Lighthouse Targets

**Performance:** 95+
- FCP: < 1.0s
- LCP: < 2.0s
- TBT: < 200ms
- CLS: < 0.1

**Accessibility:** 100
- Semantic HTML
- ARIA labels
- Keyboard navigation
- Color contrast 4.5:1+

**Best Practices:** 100
- HTTPS only
- No console errors
- Secure headers

**SEO:** 100
- Meta descriptions
- Heading hierarchy
- Valid HTML
- Sitemap

---

## 7. Analytics (Optional)

### 7.1 Plausible Analytics (Recommended)

**Why Plausible:**
- ✅ Privacy-friendly (GDPR compliant)
- ✅ Lightweight (< 1KB script)
- ✅ No cookies
- ✅ Free tier: 10K pageviews/month

**Setup:**
```html
<script defer data-domain="getpursue.app" src="https://plausible.io/js/script.js"></script>
```

**Track custom events:**
```javascript
// Track download button clicks
plausible('Download', {props: {platform: 'iOS'}});
```

### 7.2 Track Key Metrics

**Pageviews:**
- Landing page visits
- Features page views
- Pricing page views

**Conversions:**
- App Store button clicks
- Google Play button clicks
- Invite link clicks

**Engagement:**
- Time on site
- Bounce rate
- Pages per session

---

## 8. Deployment

### 8.1 Cloudflare Pages Setup

**Initial Setup:**
```bash
# 1. Push to GitHub
git init
git add .
git commit -m "Initial commit"
git remote add origin git@github.com:yourusername/pursue-site.git
git push -u origin main

# 2. Connect to Cloudflare Pages
# Go to: dash.cloudflare.com → Pages → Create project
# Connect GitHub repo: pursue-site
# Framework: Astro
# Build command: npm run build
# Output directory: dist

# 3. Deploy
# Automatic on git push to main
```

**Build Settings:**
```yaml
# Cloudflare Pages configuration
Build command: npm run build
Build output directory: /dist
Root directory: /
Environment variables:
  NODE_VERSION: 20
```

### 8.2 Custom Domain

**DNS Setup in Cloudflare:**
```
# Add CNAME record
Name: getpursue.app (or @)
Target: pursue-site.pages.dev
Proxy: Yes (orange cloud)

# Result:
# getpursue.app → Your Cloudflare Pages site
```

### 8.3 Deployment Workflow

```
1. Make changes locally
2. Test: npm run dev
3. Commit: git commit -m "Update hero copy"
4. Push: git push
5. Cloudflare auto-deploys (< 1 minute)
6. Check: getpursue.app (live)
```

**Preview deployments:**
- Every PR gets a unique URL
- Test before merging to main

---

## 9. Content Guidelines

### 9.1 Voice & Tone

**Voice:** Friendly, motivating, honest  
**Tone:** Encouraging but not pushy  
**Style:** Clear, concise, action-oriented  

**Do:**
- ✅ "Track goals together"
- ✅ "Stay accountable with friends"
- ✅ "Achieve more together"

**Don't:**
- ❌ "Leverage our platform to optimize your goal achievement paradigm"
- ❌ "Revolutionary AI-powered accountability engine"
- ❌ Overpromise ("Guarantee success!")

### 9.2 Copywriting Principles

**Headlines:**
- Lead with benefit
- Keep under 8 words
- Avoid jargon

**Body Copy:**
- Short sentences (< 20 words)
- Active voice
- Specific examples

**CTAs:**
- Action verbs (Download, Join, Start)
- Remove friction ("Free", "No credit card")
- Create urgency ("Join 1,000+ users")

---

## 10. Maintenance

### 10.1 Regular Updates

**Monthly:**
- [ ] Update screenshots (new features)
- [ ] Add user testimonials
- [ ] Check broken links
- [ ] Review analytics

**Quarterly:**
- [ ] Update privacy policy (if needed)
- [ ] Refresh blog content
- [ ] Lighthouse audit
- [ ] Competitive analysis

**Annually:**
- [ ] Redesign consideration
- [ ] Messaging refresh
- [ ] A/B test new copy

### 10.2 A/B Testing Ideas

**Test 1: Hero headline**
- A: "Track goals together"
- B: "Achieve more with friends"

**Test 2: CTA button text**
- A: "Download App"
- B: "Start Free"

**Test 3: Social proof placement**
- A: Above fold
- B: Below features

**Tool:** Cloudflare Zaraz (free A/B testing)

---

## 11. Launch Checklist

### 11.1 Pre-Launch

**Content:**
- [ ] All copy written and reviewed
- [ ] Screenshots taken (5+ screens)
- [ ] Privacy policy generated
- [ ] Terms of service generated
- [ ] Support FAQ written

**Technical:**
- [ ] Domain registered (getpursue.app)
- [ ] DNS configured (Cloudflare)
- [ ] Site deployed (Cloudflare Pages)
- [ ] SSL certificate active (auto)
- [ ] Lighthouse score 95+

**SEO:**
- [ ] Meta tags on all pages
- [ ] Open Graph images created
- [ ] Sitemap.xml submitted
- [ ] robots.txt configured
- [ ] Google Search Console setup

**App Integration:**
- [ ] Deep links configured
- [ ] assetlinks.json uploaded
- [ ] Invite flow tested
- [ ] App Store/Play Store links ready

### 11.2 Post-Launch

**Week 1:**
- [ ] Monitor analytics
- [ ] Fix any bugs
- [ ] Respond to feedback

**Week 2:**
- [ ] Start blog (optional)
- [ ] Share on social media
- [ ] Post on ProductHunt

**Month 1:**
- [ ] Gather testimonials
- [ ] Add social proof
- [ ] Optimize based on data

---

## 12. Budget Summary

**One-Time Costs:**
- Domain registration: $10.87/year
- **Total Year 1:** $10.87

**Ongoing Costs:**
- Hosting: $0 (Cloudflare Pages free)
- SSL: $0 (automatic)
- CDN: $0 (Cloudflare)
- Analytics: $0 (Plausible free tier)
- **Total Monthly:** $0.91/month (domain only)

**Optional Paid:**
- Premium analytics: $9/month (Plausible Pro)
- Privacy policy generator: $0-49 (one-time)
- Stock photos: $0 (use Unsplash free)

---

## 13. Success Metrics

### 13.1 Traffic Goals

**Month 1:** 500 visitors  
**Month 3:** 2,000 visitors  
**Month 6:** 5,000 visitors  

**Sources:**
- ProductHunt launch
- Reddit posts (r/productivity)
- App Store organic
- Word of mouth

### 13.2 Conversion Goals

**Click-through rate:** 10% (visitors → download button click)  
**Download rate:** 5% (button click → actual download)  
**Invite completion:** 60% (invite link → join group)  

### 13.3 SEO Goals

**Month 3:**
- Ranking #20-30 for "group goal tracking"
- Ranking #10-20 for "pursue app"

**Month 6:**
- Ranking #10-15 for "group goal tracking"
- Ranking #5 for "pursue app"
- 50+ organic search visitors/month

---

## Appendix A: Quick Start Commands

```bash
# Create new Astro project
npm create astro@latest pursue-site
cd pursue-site

# Install Tailwind
npx astro add tailwind

# Install dependencies
npm install

# Run dev server
npm run dev
# → http://localhost:4321

# Build for production
npm run build

# Preview production build
npm run preview

# Deploy to Cloudflare Pages
git push origin main
# → Auto-deploys
```

---

## Appendix B: Example astro.config.mjs

```javascript
import { defineConfig } from 'astro/config';
import tailwind from '@astrojs/tailwind';

export default defineConfig({
  site: 'https://getpursue.app',
  integrations: [tailwind()],
  build: {
    inlineStylesheets: 'auto',
  },
  vite: {
    build: {
      cssMinify: 'lightningcss',
    },
  },
});
```

---

## Appendix C: Example package.json

```json
{
  "name": "pursue-site",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "astro dev",
    "build": "astro build",
    "preview": "astro preview"
  },
  "dependencies": {
    "astro": "^4.1.0",
    "@astrojs/tailwind": "^5.1.0",
    "tailwindcss": "^3.4.0"
  }
}
```

---

**End of Specification**

**Next Steps:**
1. Create GitHub repo: `pursue-site`
2. Initialize Astro project
3. Build landing page
4. Deploy to Cloudflare Pages
5. Configure domain

**Estimated build time:** 4-6 hours with Cursor/Claude Code
