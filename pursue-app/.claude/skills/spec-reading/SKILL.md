---
name: spec-reading
description: This skill should be used when the user asks to "implement UI features", "create new screens", "implement backend communication", "add API endpoints", "build UI components", or needs to implement features that require referencing project specifications.
---

# Spec Reading Reminder

Always read the relevant project specification chapters before implementing new features. Specs are split by chapter under `specs/ui/` and `specs/backend/`.

## When to Read Specs

### For UI Features

**Source: `specs/ui/`**

List the directory to see available chapters, then read all relevant ones for the task. Typically include overview, screen specifications, and accessibility chapters. Also read design system and navigation chapters when implementing components or navigation flows.

Use when implementing: new screens or fragments, UI components and layouts, navigation flows, user interactions, design system elements, accessibility features.

### For Backend Communication

**Source: `specs/backend/`**

List the directory to see available chapters, then read the overview plus any chapters relevant to your specific task (e.g., API endpoints, authentication, database schema, error handling).

Use when implementing: API client methods, network requests and responses, authentication flows, data models and serialization, error handling for API calls, backend integration.

## Workflow

1. **Identify the feature type** - UI or backend communication
2. **List the chapter files** - Use `list_dir` on `specs/ui/` and/or `specs/backend/` to see all available chapters
3. **Read the relevant chapters** - Read all chapters that are relevant to the current task
4. **Follow the specifications** - Implement according to the documented requirements
5. **Reference during implementation** - Keep the read chapters in mind while coding
