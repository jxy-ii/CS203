# Policy Impact

Java/Spring Boot backend for profile-aware immigration policy retrieval and impact assessment.

## Requirements

- Java 21+
- Docker Desktop (for PostgreSQL with pgvector)
- Ollama with the `mxbai-embed-large` and `llama3.2` models

Set `JAVA_HOME` to your JDK installation before using the Maven Wrapper.

## Run locally in WSL

From the cloned `CS203` directory, start PostgreSQL and install the Ollama models:

```bash
docker compose up -d
ollama pull mxbai-embed-large
ollama pull llama3.2
```

Make sure the Ollama server is running. If it is not already running, use a separate
terminal and keep it open:

```bash
ollama serve
```

Start Spring Boot from the `CS203` directory:

```bash
./mvnw spring-boot:run
```

Wait for `Started MyGrantApplication` before testing. Keep this terminal open while
using the API or browser interface.

The health endpoint is available at `http://localhost:8080/actuator/health`.
Swagger UI is available at `http://localhost:8080/swagger-ui.html`.
The interactive myGRANT prototype is available at `http://localhost:8080/`.

Configuration can be overridden with `DATABASE_URL`, `DATABASE_USERNAME`,
`DATABASE_PASSWORD`, `OLLAMA_BASE_URL`, and `OLLAMA_EMBEDDING_MODEL`.

## Architecture

The existing top-level `policies`, `ingestion`, and `rag` directories contain their
respective Java feature packages. Maven registers them as additional source directories.
The Spring Boot entry point and shared application infrastructure remain under
`src/main/java/mygrant`.

Spring AI creates the `policy_chunks` vector table on first startup. Schema-name
validation is intentionally disabled during automatic initialization because validation
requires the table to exist before it can be created.
The vector dimension is `1024`, matching the configured `mxbai-embed-large` model.

The implemented RAG path is:

```text
POST /ingestion/documents
  -> save the original policy in policy_documents
  -> split the policy into chunks
  -> create Ollama embeddings
  -> store chunks and citation metadata in pgvector

POST /rag/query
  -> embed the question
  -> retrieve relevant chunks filtered by visa type
  -> rank primary/effective evidence first
  -> generate a grounded Ollama answer
  -> return citations, confidence and review status
```

## API

Ingest a recent policy document:

```http
POST /ingestion/documents
Content-Type: application/json

{
  "externalId": "2026-14439",
  "title": "Establishing a Fixed Time Period of Admission",
  "agency": "Department of Homeland Security",
  "visaType": "F-1",
  "status": "FINAL",
  "sourceType": "PRIMARY",
  "publicationDate": "2026-07-17",
  "effectiveDate": "2026-09-15",
  "sourceUrl": "https://www.federalregister.gov/documents/2026/07/17/2026-14439/establishing-a-fixed-time-period-of-admission-and-an-extension-of-stay-procedure-for-nonimmigrant",
  "content": "Paste the relevant official source text here."
}
```

Query the indexed evidence:

```http
POST /rag/query
Content-Type: application/json

{
  "question": "I am an F-1 PhD student in a five-year program. How could the new rule affect me?",
  "visaType": "F-1"
}
```

Read stored policy records with `GET /policies` or `GET /policies/{id}`.

## Test RAG from the terminal

Open a second WSL terminal in the `CS203` directory while Spring Boot is still running.

### 1. Check the services

```bash
curl -s http://localhost:8080/actuator/health
ollama list
docker compose ps
```

The health response should contain `"status":"UP"`. The Ollama list should contain
`mxbai-embed-large` and `llama3.2`, and PostgreSQL should be healthy.

### 2. Ingest a controlled test document

This document contains a unique phrase so the result cannot come from a hardcoded UI
answer:

```bash
curl -s -X POST http://localhost:8080/ingestion/documents \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "MYGRANT-TERMINAL-RAG-TEST-001",
    "title": "myGRANT Terminal RAG Test Policy",
    "agency": "Demo Verification Agency",
    "visaType": "F-1",
    "status": "FINAL",
    "sourceType": "PRIMARY",
    "publicationDate": "2026-09-15",
    "effectiveDate": "2026-09-15",
    "sourceUrl": "https://example.com/mygrant-terminal-test",
    "content": "For this controlled test policy, an F-1 student must notify the university international office exactly 37 days before international travel. The unique verification phrase is BLUE-ORCHID-37."
  }'
```

A successful response includes `"chunksIndexed":1`. Running the same command again is
safe and returns `"alreadyIndexed":true`.

### 3. Query the indexed evidence

```bash
curl -s -X POST http://localhost:8080/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "How many days before international travel must the F-1 student notify the university office, and what is the verification phrase?",
    "visaType": "F-1"
  }'
```

The answer should contain `37 days` and `BLUE-ORCHID-37`. Its citations should contain
`MYGRANT-TERMINAL-RAG-TEST-001`. This verifies embedding, pgvector retrieval, grounded
generation, and citation creation.

For formatted JSON, append `| jq` to a command if `jq` is installed.

### 4. Verify the visa filter

Ask for the same evidence using a different visa type:

```bash
curl -s -X POST http://localhost:8080/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What is the BLUE-ORCHID-37 travel requirement?",
    "visaType": "H-1B"
  }'
```

Because the test document is tagged `F-1`, the expected response has `LOW` confidence,
`requiresReview: true`, and no citations. This confirms that retrieval applies the visa
filter rather than returning a fixed answer.

## Run automated tests

```bash
./mvnw test
```
