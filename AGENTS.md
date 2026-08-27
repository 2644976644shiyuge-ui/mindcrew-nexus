# Repository guidance

## Project layout

- The backend is a Java 17 Spring Boot application in `src/`, built with Maven.
- The Vue 3 and TypeScript frontend is in `MindCrew-frontend/`, built with Vite.
- Docker Compose files and deployment scripts at the repository root coordinate MySQL, Redis, MinIO, Milvus, the backend, and the frontend.
- Database schema and seed changes live in `sql/`. Treat existing migrations and production data as compatibility-sensitive.

## Change boundaries

- Never commit real credentials, API keys, `.env` files, private certificates, database backups, uploaded knowledge documents, runtime logs, Docker volumes, or generated build output.
- Keep environment templates free of usable secrets. Use empty values or clearly non-secret placeholders.
- Preserve tenant, organization, user, and knowledge-base authorization checks across controllers, services, repositories, background jobs, and streaming endpoints.
- For RAG changes, keep source attribution, knowledge-base scoping, retrieval fallbacks, and answer-grounding behavior intact unless the change explicitly redesigns them.
- For global lead acquisition, do not expose provider credentials or silently weaken concurrency, retry, deduplication, validation, or rate-limit safeguards.
- Do not claim that uploaded knowledge data is versioned in Git; it lives in runtime storage and must be backed up separately.

## Validation

- Backend behavior changes: run `mvn test` from the repository root.
- Frontend behavior changes: run `npm run build` from `MindCrew-frontend/`.
- Docker or configuration changes: run `docker compose config` with the intended environment variables available.
- Documentation-only changes may skip builds, but must pass `git diff --check`.

## Code review rules

### Security and data isolation

- Flag authentication or authorization bypasses, cross-tenant data exposure, unsafe file handling, secrets in source, and unbounded external requests as high priority.
- Verify that new endpoints and asynchronous tasks enforce the same access scope as their synchronous callers.

### Reliability

- Flag blocking network or model calls on shared request threads when they can cause queue starvation, deadlocks, or cascading timeouts.
- Require explicit timeouts, bounded concurrency, cancellation, retry limits, and idempotency where external AI, search, email, OCR, or lead-data providers are called.

### Retrieval quality

- Check that retrieval changes handle product aliases and model numbers, combine semantic and lexical evidence where appropriate, and avoid declaring that knowledge is absent before fallback retrieval finishes.
- Preserve citations and distinguish evidence-backed facts from inference or live-web enrichment.

### Frontend behavior

- Check keyboard focus, readable contrast, scroll containment, responsive layout, loading and error states, and reduced-motion support.
- Reject global CSS fixes that remove accessible focus indicators; style focus states intentionally with `:focus-visible`.
