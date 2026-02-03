# Pursue Marketing Site

Marketing site for the Pursue mobile app: convert visitors to app downloads, explain the product, handle invite deep links, and host legal and support content.

**Stack:** Astro 4.x, Tailwind CSS 3.x, TypeScript. Target hosting: Cloudflare Pages. Domain: getpursue.app.

## Local setup

**Prerequisites:** Node.js 18+.

```bash
npm install   # run first to install dependencies
npm run dev
```

Dev server: http://localhost:4321. After `npm install`, `npm run build` produces the `dist/` output for deployment.

## Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start dev server |
| `npm run build` | Production build (output: `dist/`) |
| `npm run preview` | Preview production build locally |

## Deploy

Deploy to Cloudflare Pages with build command `npm run build` and output directory `dist/`. See spec §8 for DNS and domain setup.

## Assets

- **Hero image:** Add `public/screenshots/today-screen.png` for the landing hero (Today screen screenshot). Until then, the hero shows a placeholder area.
- **Google Play badge:** The site uses a placeholder SVG at `public/badges/get-it-on-google-play.svg`. For official branding, download the "Get it on Google Play" badge (English SVG) from [Google Play Badge Guidelines](https://partnermarketinghub.withgoogle.com/brands/google-play/visual-identity/badge-guidelines/?folder=86718) and replace that file.
- **Invite URLs:** The invite handler is built at `/invite/join`. For production, add a rewrite so `/invite/*` serves `/invite/join` (e.g. Cloudflare Pages redirect/rewrite). The client script reads the invite code from the URL path.

## Spec

Full requirements: [specs/pursue-site-spec.md](specs/pursue-site-spec.md).
