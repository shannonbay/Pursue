# Pursue App Release Notes

## Version 1.1.0 (Build 2)
**Release Date:** February 28, 2026

### ✨ New Features

#### Spanish Language Support
- Full Spanish (Español) localization for all app screens and UI elements
- Supports regional Spanish variants (Spain, Mexico, Argentina, etc.) with automatic fallback to neutral Spanish
- 960+ strings translated to Spanish covering:
  - Goal creation and progress logging
  - Group management and member invitations
  - Discover public groups and templates
  - User profile and settings
  - All onboarding and authentication flows
  - Push notifications and app messages

#### Backend i18n Infrastructure
- Language fallback system for regional dialect support (es-ES → es, pt-BR → pt)
- 80 group/goal templates now available in Spanish
- API returns translated content based on device language settings

### 🛠️ Technical Improvements

- Added language variant utility (`language.ts`) for handling BCP 47 language tags
- Enhanced template and goal APIs with language preference fallback logic
- Improved category filter translations in templates browser

### 🐛 Bug Fixes

- Fixed category filter chips not displaying translated text in challenges/templates browser
- Ensured consistent translation behavior across all app screens

### 📱 Requirements

- **Android:** 6.0 (API 24) or higher
- **Backend:** Version 1.1.0 or later (required for Spanish translations)

### 🔄 Migration Notes

- No breaking changes to existing functionality
- English and Portuguese users unaffected
- New Spanish users can use the entire app in their preferred language

---

## Version 1.0 (Build 1)
**Release Date:** February 2026

### Initial Release

First production release of Pursue with core functionality:
- Goal creation and progress tracking
- Group management and member collaboration
- Push notifications (FCM)
- User authentication (email, Google OAuth)
- Public group discovery
- Goal templates and challenges
- Portuguese language support (pt-BR)
