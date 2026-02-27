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