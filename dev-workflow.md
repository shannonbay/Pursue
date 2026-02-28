## First get Opus 4.6 to write the spe

Then copy the spec to specs/
Update the Claude Pursue project-level Pursue Features list with any major new features

## COMPACT or RESTART Claude before next step

## Ask an agent in planning mode to implement the backend for the spec with full test coverage - Can use Sonnet or Auto for backend builds
PLANNING MODE
`Implement the backend for specs/*.md with full test coverage - don't forget to update schema.sql and pursue-server/migrations if needed.  Run the relevant E2E tests in pursue-app (see E2ETESTING.md) - the local dev server is running.`

## Ask an agent in planning mode to implement E2E tests
prmopt template:
PLANNING MODE
`Implement or update E2E tests for specs/*.md - you may need to update ApiClient.kt and E2EApiClient.kt Refer to E2ETESTING.md The dev server is currently running`

Make sure the agent runs the tests or run them yourself.

## Ask an agent in planning mode to implement frontend UI and App functionality for the spec
PLANNING MODE
`Implement frontend UI and app functionality for specs/*.md - Refer to specs/ui/02-design-system.md`

## Translations
Create a Mandarin Chinese language translation of the Pursue App.  The server infrastructure for multilanguage support already exists.  You just need to create a new strings.xml resource and a templates provisioning database migration. Use english as the canonical source material.  The canonical source material for templates is pursue-server/migrations/group_goal_templates.json - you'll need to create a migration with a similar structure to pursue-server/migrations/20260228_add_template_translations.sql
There's functionality in Android and in the server to fall back from dialects to a base language.  Let's implement a base language that varaious dialects will be able to fall back to.
You do all the translation - I'll review it with native language speakers after the fact.
Prefer a casual and friendly tone.

I need to add French (fr) language support to the Pursue app by translating all Android UI strings and backend templates/goals.

**Target Language Details:**
- Language: French
- Locale code: fr
- Translation tone: casual and friendly, accessible to all French speakers
- Terminology: Use "objectif" for goals, "groupe" for groups

**Android Strings (820 strings total)**
Source: /mnt/c/Users/layen/GitHub/Pursue/pursue-app/app/src/main/res/values/strings.xml
Output: /mnt/c/Users/layen/GitHub/Pursue/pursue-app/app/src/main/res/values-fr/strings.xml

**Backend Group Templates (68 templates + 80 goals)**
Reference: /mnt/c/Users/layen/GitHub/Pursue/pursue-server/migrations/group_goal_templates.json
Output: /mnt/c/Users/layen/GitHub/Pursue/pursue-server/migrations/20260228_add_fr_template_translations.sql

Remember to update /mnt/c/Users/layen/GitHub/Pursue/pursue-app/app/src/main/res/xml/locales_config.xml