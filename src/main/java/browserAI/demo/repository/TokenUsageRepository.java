package browserAI.demo.repository;

import browserAI.demo.model.entity.TokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    Optional<TokenUsage> findBySessionId(String sessionId);

    List<TokenUsage> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("SELECT COALESCE(SUM(t.totalTokens), 0) FROM TokenUsage t WHERE t.userId = :userId")
    long sumTotalTokensByUserId(String userId);

    @Query("SELECT COALESCE(SUM(t.totalTokens), 0) FROM TokenUsage t")
    long sumAllTokens();

    @Query("SELECT COALESCE(SUM(t.llmCalls), 0) FROM TokenUsage t")
    long sumAllLlmCalls();

    @Query("SELECT COALESCE(SUM(t.llmCalls), 0) FROM TokenUsage t WHERE t.userId = :userId")
    long sumLlmCallsByUserId(String userId);
}
