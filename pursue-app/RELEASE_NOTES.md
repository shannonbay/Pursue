# Pursue App Release Notes

## Version 1.2.0 (Build 3)
**Release Date:** February 28, 2026

### ✨ New Features

#### Expanded Language Support
Pursue now supports five major languages, bringing the app to users worldwide:

**French (Français)**
- Full French localization for all app screens and UI elements
- Casual, friendly tone with native French terminology
- 820 app strings + 68 templates + 80 goals translated
- Supports regional French variants (France, Canada, Belgium, Switzerland) with automatic fallback to base French
- Complete coverage of:
  - Goal creation and progress logging ("Ziel" / "Groupe" terminology)
  - Group management and member collaboration
  - Discover public groups and templates
  - User profile, settings, and notifications

**German (Deutsch)**
- Full German localization across all app features
- Casual, accessible tone for all German speakers
- 820 app strings + 68 templates + 80 goals translated
- Supports regional German variants (Germany, Austria, Switzerland) with automatic fallback to base German
- Complete coverage of:
  - Goal and group workflows with native "Ziel" and "Gruppe" terminology
  - All template categories and challenge setups
  - Community features and notifications
  - User onboarding and authentication

**Mandarin Chinese (中文)**
- Full Mandarin Chinese localization for enhanced Asian market reach
- 820 app strings + 68 templates + 80 goals translated
- Supports regional variants with automatic fallback to base Chinese
- Comprehensive coverage of:
  - Goal setting and progress tracking
  - Group collaboration features
  - Template discovery and challenges
  - All user-facing strings and notifications

#### Backend Language Support
- All three languages available through backend API with language preference detection
- Automatic language fallback for regional variants (fr-CA → fr, de-AT → de, zh-TW → zh)
- 204+ new template and goal translations (68 templates × 3 languages, 80 goals × 3 languages)
- Language-aware API responses based on `Accept-Language` headers and app locale

### 🌍 Supported Languages
- English (en) - default
- Spanish (es)
- French (fr)
- German (de)
- Portuguese Brazilian (pt-BR)
- Mandarin Chinese (zh)

### 🛠️ Technical Improvements

- Enhanced locale configuration to support 6 languages with regional fallbacks
- Localized template discovery with language-specific category translations
- Improved language detection and automatic fallback logic
- Database migrations for multi-language template and goal translations

### 🐛 Bug Fixes

- Ensured consistent translation behavior across all app screens and languages
- Fixed language fallback chain for regional variants

### 📱 Requirements

- **Android:** 6.0 (API 24) or higher
- **Backend:** Version 1.2.0 or later (required for French, German, and Chinese translations)

### 🔄 Migration Notes

- No breaking changes to existing functionality
- Existing users see content in their device language automatically
- All five supported languages share the same backend and feature set
- Users can switch languages anytime via device settings

---

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
