## First get Opus 4.6 to write the spec
Then copy the spec to specs/
Update the Claude Pursue project-level Pursue Features list with any major new features

## Ask an agent in planning mode to implement the backend for the spec with full test coverage - Can use Sonnet or Auto for backend builds
`Implement the backend for specs/*.md with full test coverage`

## Ask an agent in planning mode to implement E2E tests
prmopt template:
`Implement E2E tests for specs/*.md - you will need to update ApiClient.kt and E2EApiClient.kt Refer to E2ETESTING.md`

Make sure the agent runs the tests or run them yourself.

## Ask an agent in planning mode to implement frontend UI and App functionality for the spec
`Implement frontend UI and app functionality for specs/*.md - Refer to specs/ui/02-design-system.md`