# BrowserAI Agent

An AI-powered browser automation system built with **Spring Boot** and **Google Gemini** that downloads documents from any website using natural language commands. It dynamically navigates portals, handles logins, OTPs, CAPTCHAs, and learns workflows to minimize LLM usage over time.

> **"Download my IndiGo invoice for PNR ABC123"** — and the agent figures out the rest.

**Documentation:**
- [**Interview preparation**](docs/INTERVIEW_PREP.md) — how to explain the project, architecture diagrams, flow, tech stack, and sample Q&A.
- [**Architecture one-pager**](docs/ARCHITECTURE.md) — diagrams and quick reference.

---

## What Problem Does This Solve?

Downloading documents from portals (airlines, banks, utilities, government sites) typically requires:
- Navigating complex multi-step forms
- Logging in with credentials
- Handling OTPs and CAPTCHAs
- Finding the right download button

This agent automates the **entire flow** using AI — no hardcoded scripts per portal. It works with **any website** by understanding the DOM in real-time.

---

## Key Features

| Feature | Description |
|---------|-------------|
| **Natural Language Input** | Send requests in English, Hindi, or Hinglish — the AI extracts intent |
| **Zero-Hardcode Portal Support** | Works with any portal via LLM-guided DOM exploration |
| **Workflow Learning** | Caches successful navigation paths for zero-token replay |
| **Credential Vault** | AES-256-GCM encrypted storage — credentials never sent to LLM |
| **Document Deduplication** | Pre-execution (reference match) + post-download (SHA-256 hash) |
| **Document Intelligence** | Extracts structured data from PDFs (invoice number, amount, dates) |
| **OTP & CAPTCHA Handling** | AI-powered CAPTCHA solving with manual fallback; OTP via API |
| **Scheduled Downloads** | Cron-based recurring downloads and auto web check-in |
| **Batch Downloads** | Multiple references in a single request |
| **Natural Language Queries** | Ask questions about your downloaded documents |
| **Governance & Safety** | Configurable limits on LLM calls, tokens, session duration |
| **Full Audit Trail** | Every action logged with timestamps and token usage |
| **Rate Limiting** | Per-user sliding window (Redis-backed) |
| **Auto-Save** | Downloads automatically copied to local `~/Downloads` |

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│                   REST API Layer                  │
│              (AgentController.java)               │
└──────────────────┬───────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────┐
│              Agent Orchestrator                   │
│    (FSM state machine + conversation flow)        │
└──┬──────────┬──────────┬──────────┬──────────────┘
   │          │          │          │
   ▼          ▼          ▼          ▼
┌──────┐ ┌────────┐ ┌────────┐ ┌──────────┐
│Gemini│ │Playwright│ │Credential│ │ Memory  │
│  LLM │ │ Browser │ │  Vault  │ │ (Redis) │
└──────┘ └────────┘ └────────┘ └──────────┘
   │          │          │          │
   ▼          ▼          ▼          ▼
┌──────────────────────────────────────────────────┐
│                  PostgreSQL                       │
│  (documents, workflows, audit, token tracking)    │
└──────────────────────────────────────────────────┘
```

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2.5 (Java 17) |
| AI/LLM | Google Gemini 2.0 Flash |
| Browser Automation | Playwright 1.44.0 (Chromium) |
| Database | PostgreSQL 16 |
| Cache & Locks | Redis 7 |
| Resilience | Resilience4j (Circuit Breaker + Retry) |
| PDF Processing | Apache PDFBox 2.0.31 |
| Search | SerpAPI (portal URL discovery) |
| Containerization | Docker + Docker Compose |

---

## How It Works

```
User Request → Intent Extraction (Gemini) → Credential Lookup
     → Check Cached Workflow → Playwright Browser Launch
     → DOM Exploration (LLM-guided) → Login → Handle OTP/CAPTCHA
     → Navigate to Document → Download → Hash & Dedup
     → Extract PDF Data → Cache Workflow → Return Response
```

1. **User sends a natural language request** via REST API
2. **Gemini extracts intent** — portal, document type, reference number
3. **Workflow cache check** — if a cached path exists, replay it (zero LLM tokens)
4. **Playwright launches Chromium** and navigates to the portal
5. **LLM-guided DOM exploration** — reads the page, decides what to click/fill
6. **Handles authentication** — decrypts credentials, fills login forms
7. **Handles OTP/CAPTCHA** — waits for user input or solves with AI
8. **Downloads the document** and computes SHA-256 hash
9. **Deduplication check** — returns existing document if already downloaded
10. **Extracts structured data** from PDFs using Gemini
11. **Caches the workflow** for future zero-token replay
12. **Returns response** with document info, download URL, and extracted data

---

## API Endpoints

### Core

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/request` | Main agent endpoint — natural language input |
| `POST` | `/api/credentials` | Store portal credentials (AES-256 encrypted) |
| `POST` | `/api/otp` | Submit OTP for pending browser session |
| `POST` | `/api/captcha` | Submit CAPTCHA answer |
| `GET` | `/api/captcha/{userId}` | Get CAPTCHA image (base64 JSON) |
| `GET` | `/api/captcha/{userId}/image` | Get CAPTCHA image (raw PNG) |
| `GET` | `/api/download/{id}` | Download stored document |

### Scheduling

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/check-in/schedule` | Schedule auto web check-in |
| `GET` | `/api/check-in/list` | List scheduled check-ins |
| `DELETE` | `/api/check-in/{taskId}` | Cancel a scheduled check-in |

### Monitoring

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/audit/{sessionId}` | Get execution audit trail |
| `GET` | `/api/tokens` | Get token usage stats |
| `GET` | `/api/health` | Health check |

### Example Request

```bash
curl -X POST http://localhost:8080/api/request \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user1",
    "input": "Download my IndiGo invoice for PNR ABC123"
  }'
```

### Example Response

```json
{
  "success": true,
  "message": "Document downloaded successfully",
  "document": {
    "id": 1,
    "portal": "indigo",
    "documentType": "invoice",
    "reference": "ABC123",
    "fileName": "indigo_invoice_ABC123_a1b2c3d4.pdf",
    "downloadUrl": "/api/download/1",
    "hash": "sha256...",
    "duplicate": false
  },
  "extractedData": {
    "invoiceNumber": "ABC123",
    "totalAmount": "₹4,523.00",
    "vendorName": "IndiGo Airlines"
  },
  "tokenUsage": {
    "inputTokens": 500,
    "outputTokens": 200,
    "totalTokens": 700
  }
}
```

---

## Database Schema

| Table | Purpose |
|-------|---------|
| `documents` | Downloaded document metadata + SHA-256 hash |
| `credentials` | AES-256-GCM encrypted portal credentials |
| `conversation_sessions` | FSM state for multi-turn conversations |
| `audit_logs` | Full execution audit trail |
| `session_summaries` | Memory/RAG summaries (pgvector-ready) |
| `portal_workflows` | Learned automation paths for replay |
| `token_usage` | Gemini token consumption tracking |
| `agent_request_logs` | Request tracking with unique IDs |
| `extracted_document_data` | Structured data extracted from PDFs |
| `scheduled_tasks` | Cron-based and one-time scheduled tasks |

---

## Getting Started

### Prerequisites

- Java 17+
- PostgreSQL 16
- Redis 7
- Google Gemini API key ([Get one here](https://aistudio.google.com/app/apikey))

### 1. Clone the repo

```bash
git clone https://github.com/<your-username>/browserai-agent.git
cd browserai-agent
```

### 2. Set up environment variables

```bash
cp .env.example .env
```

Edit `.env` and fill in your values:

```env
GEMINI_API_KEY=your_gemini_api_key_here
SERPAPI_API_KEY=your_serpapi_api_key_here
ENCRYPTION_SECRET=your_32_character_secret_key_here
DB_PASSWORD=your_db_password_here
```

### 3. Option A: Run with Docker Compose (recommended)

```bash
docker compose up -d
```

This starts PostgreSQL, Redis, and the application automatically.

### 3. Option B: Run locally

```bash
# Start PostgreSQL and Redis (via Docker or locally)
docker compose up postgres redis -d

# Initialize the database
psql -U browserai -d browserai -f src/main/resources/schema.sql

# Install Playwright browsers
npx playwright install chromium

# Run the application
./gradlew bootRun
```

### 4. Verify

```bash
curl http://localhost:8080/api/health
```

---

## Configuration

All configuration is via environment variables. See [`.env.example`](.env.example) for the full list.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GEMINI_API_KEY` | Yes | — | Google Gemini API key |
| `ENCRYPTION_SECRET` | Yes | — | AES-256 secret (exactly 32 chars) |
| `DB_PASSWORD` | Yes | — | PostgreSQL password |
| `SERPAPI_API_KEY` | No | — | SerpAPI key for portal URL discovery |
| `DB_HOST` | No | `localhost` | PostgreSQL host |
| `DB_PORT` | No | `5433` | PostgreSQL port |
| `DB_NAME` | No | `browserai` | Database name |
| `DB_USERNAME` | No | `browserai` | Database username |
| `REDIS_HOST` | No | `localhost` | Redis host |
| `REDIS_PORT` | No | `6379` | Redis port |
| `SERVER_PORT` | No | `8080` | Application port |
| `PLAYWRIGHT_HEADLESS` | No | `false` | Run browser headless |
| `MAX_LLM_CALLS` | No | `25` | Max Gemini calls per session |
| `MAX_TOKENS` | No | `100000` | Max tokens per session |
| `DOWNLOAD_TIMEOUT` | No | `90` | Download timeout in seconds |
| `LOG_LEVEL` | No | `DEBUG` | Application log level |

---

## Project Structure

```
src/main/java/browserAI/demo/
├── controller/          # REST API endpoints
├── service/             # Business logic
│   ├── AgentOrchestrator    # Main orchestration engine
│   ├── GeminiService        # LLM integration
│   ├── DomExplorationService # DOM analysis
│   ├── CredentialVaultService # Encrypted credential storage
│   ├── MemoryService        # Workflow caching & session memory
│   ├── GovernanceService    # Safety limits & guardrails
│   ├── DocumentProcessingService # Hash, dedup, storage
│   ├── DocumentIntelligenceService # PDF data extraction
│   ├── CaptchaSolverService # AI + manual CAPTCHA solving
│   ├── OtpService           # OTP handling via Redis
│   ├── WebSearchService     # Portal URL discovery (SerpAPI)
│   ├── SchedulerService     # Cron & one-time task scheduling
│   └── ...
├── portal/              # Browser automation
│   ├── DynamicPortalAdapter # LLM-guided portal navigation
│   ├── PortalAdapter        # Portal interface
│   └── PortalExecutorFactory
├── model/
│   ├── dto/             # Request/Response DTOs
│   └── entity/          # JPA entities
├── repository/          # Spring Data JPA repositories
├── fsm/                 # Conversation state machine
├── captcha/             # CAPTCHA detection & solving
├── config/              # Spring Security, Redis, Scheduler configs
├── exception/           # Custom exceptions & global handler
└── util/                # Encryption, hashing, log masking
```

---

## Security

- **Credential Encryption**: AES-256-GCM with random IV per encryption
- **Credentials never sent to LLM**: Decrypted only in-memory during browser automation
- **Rate Limiting**: Per-user sliding window (10/min, 60/hour)
- **Log Masking**: Passwords and emails masked in all logs
- **Governance Limits**: Configurable caps on LLM calls, tokens, and session duration
- **Document Access Control**: Users can only download their own documents
- **Stateless Sessions**: No server-side session state

> **Note:** Security config currently permits all requests (suitable for local/demo use). For production, add JWT authentication and CORS configuration.

---

## Supported Portals

The agent works with **any portal** — no hardcoded scripts. Some tested examples:

- Airlines (IndiGo, Air India, SpiceJet, etc.)
- Banks and financial portals
- E-commerce platforms
- Government portals
- Utility providers

The LLM dynamically explores each portal's DOM and learns the navigation workflow for future use.

---

## License

This project is for educational and demonstration purposes.

---

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

1. Fork the repo
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
