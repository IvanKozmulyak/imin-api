# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

- **Language**: Java 17
- **Framework**: Spring Boot 4.0.5
- **Build Tool**: Maven (via `./mvnw` wrapper)
- **Database**: PostgreSQL (managed via Docker Compose)
- **ORM**: Spring Data JPA with Liquibase migrations
- **REST**: Spring Data REST (auto-generated endpoints) + SpringDoc OpenAPI
- **Security**: Spring Security with SAML2
- **AI**: Spring AI with OpenAI integration
- **Code generation**: Lombok

## Commands

```bash
# Start the PostgreSQL dev database
docker compose up -d

# Run the application
./mvnw spring-boot:run

# Build
./mvnw clean package

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Run a single test method
./mvnw test -Dtest=ClassName#methodName
```

## Architecture

This is a green-field Spring Boot REST API. The entry point is `IminApiApplication.java` at `src/main/java/com/imin/iminapi/`.

**Key conventions to follow as the codebase grows:**

- **Database schema**: Managed exclusively via Liquibase changesets in `src/main/resources/db/changelog/`. Never modify the schema directly; always add a new changeset.
- **REST endpoints**: Prefer Spring Data REST repositories for standard CRUD. Use `@RestController` only when custom logic is needed.
- **Configuration**: Use `src/main/resources/application.yaml`. Sensitive values (DB credentials, API keys) should come from environment variables, not be hardcoded.
- **Local dev database**: `compose.yaml` runs PostgreSQL with DB `mydatabase`, user `myuser`, password `secret`.
- **API docs**: SpringDoc OpenAPI is included — Swagger UI is available at `/swagger-ui.html` when running locally.