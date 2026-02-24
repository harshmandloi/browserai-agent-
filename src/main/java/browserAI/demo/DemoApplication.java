package browserAI.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Browser Agent — Gemini-powered document download automation.
 *
 * Architecture:
 *   User Input -> Gemini Intent Extraction -> Agent Orchestrator
 *   -> Credential Vault -> Playwright Portal Executor -> Document Processor
 *   -> SHA-256 Hash -> Dedup Check -> Storage -> Response
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
