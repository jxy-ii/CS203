# Policy Impact

Java/Spring Boot backend for profile-aware immigration policy retrieval and impact assessment.

## Requirements

- Java 21+
- Docker Desktop (for PostgreSQL with pgvector)
- Ollama with the `mxbai-embed-large` and `llama3.2` models

Set `JAVA_HOME` to your JDK installation before using the Maven Wrapper.

## Run locally in WSL

From the cloned `CS203` directory (not the parent `Project` directory), start
PostgreSQL and install the Ollama models:

```bash
docker compose up -d
docker compose ps
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
using the API or browser interface. After code changes, stop Spring with `Ctrl+C`
and run `./mvnw spring-boot:run` again; a running JVM does not reload Java changes.

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
  "externalId": "MYGRANT-MANUAL-EXAMPLE",
  "title": "Manual policy example",
  "agency": "Example agency",
  "visaType": "F-1",
  "status": "FINAL",
  "sourceType": "PRIMARY",
  "publicationDate": "2026-07-17",
  "effectiveDate": "2026-09-15",
  "sourceUrl": "https://example.com/manual-policy",
  "content": "Replace this text with the source policy before using it for RAG."
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

## Import and classify a Federal Register document

The Federal Register API does not require an API key. The import endpoint downloads a
document's metadata and complete raw text, detects supported visa categories from policy
language, and passes the result through the normal PostgreSQL and pgvector ingestion path.
The complete source text is retained in PostgreSQL. To keep the first demo's embedding
job manageable, only its opening 6,000 characters (including the rule summary) are
indexed in pgvector; RAG cannot answer questions about sections outside that excerpt.

Restart Spring Boot after pulling new code, then import a document by its Federal Register
document number:

```bash
curl -s -X POST \
  http://localhost:8080/ingestion/federal-register/2026-14439 | jq
```

The response includes classification flags and the signals that caused each match:

```json
{
  "documentNumber": "2026-14439",
  "affectsF1": true,
  "affectsJ1": true,
  "affectsH1b": false,
  "detectedVisaTypes": ["F-1", "J-1"],
  "matchedSignals": ["F-1:academic student", "J-1:exchange visitor"],
  "ingestion": { "chunksIndexed": 6, "alreadyIndexed": false }
}
```

The chunk count can vary. Importing the same unchanged document again returns
`"alreadyIndexed":true` and `"chunksIndexed":0`; this means the existing vectors were
reused, not that no vectors exist.

Confirm the metadata was stored:

```bash
curl -s http://localhost:8080/policies | jq
```

Then confirm that its vector chunks are retrievable for F-1:

```bash
curl -s -X POST http://localhost:8080/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What does the fixed period of admission policy change for F-1 students?",
    "visaType": "F-1"
  }' | jq
```

### How visa classification works

Classification is a deterministic keyword heuristic, **not** an LLM or RAG decision.
It checks the document title first. If the title names a supported category, only its
title matches are used; this avoids classifying a rule as H-1B merely because H-1B
appears in background comparisons. If the title has no supported signal, it scans the
full text. It reports the matched signals and the booleans `affectsF1`, `affectsJ1`,
and `affectsH1b`. Supported categories are F-1, J-1, and H-1B. An unmatched document
returns HTTP 400 and is not ingested. A match is a routing hint, **not a legal
determination**; review ambiguous policies before sending personalised alerts.

For document `2026-14439`, the title signals `academic student` and `exchange visitor`
produce F-1 and J-1 matches, while `affectsH1b` is false. The saved policy stores the
full source text; only the opening 6,000 characters are searchable in pgvector for this
demo. RAG retrieves those chunks after classification.

## Test RAG from the terminal

Open a second WSL terminal while Spring Boot is still running. `jq` formats the JSON;
remove `| jq` if it is not installed.

### 1. Check the services

```bash
curl -s http://localhost:8080/actuator/health
ollama list
docker compose ps
```

The health response should contain `"status":"UP"`; PostgreSQL should be healthy and
both Ollama models should be listed.

### 2. Ask an F-1 question

```bash
curl -s -X POST http://localhost:8080/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What does the fixed period of admission rule change for F-1 students, and when is it effective?",
    "visaType": "F-1"
  }' | jq
```

Look for citation `2026-14439` and an answer about changing from duration of status to
a fixed admission period, effective September 15, 2026. `confidence: LOW` and
`requiresReview: true` are possible; they mean the answer needs human review, not that
the API failed.

### 3. Ask a J-1 question

```bash
curl -s -X POST http://localhost:8080/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Does the fixed period of admission rule also apply to J-1 exchange visitors?",
    "visaType": "J-1"
  }' | jq
```

Check for citation `2026-14439` and compare the answer with the linked source.

### 4. Verify the visa filter

Ask about this F-1/J-1 rule using an H-1B filter:

```bash
curl -s -X POST http://localhost:8080/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What does rule 2026-14439 change for F-1 students?",
    "visaType": "H-1B"
  }' | jq
```

The response must **not** cite `2026-14439`. It may cite other H-1B documents if you
have indexed them. Do not judge correctness from answer text alone: inspect `citations`,
`confidence`, `requiresReview`, and `warnings`.

## Run automated tests

```bash
./mvnw test
```
