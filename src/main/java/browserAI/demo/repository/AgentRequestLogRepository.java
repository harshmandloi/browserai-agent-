package browserAI.demo.repository;

import browserAI.demo.model.entity.AgentRequestLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRequestLogRepository extends JpaRepository<AgentRequestLog, Long> {

    Optional<AgentRequestLog> findByRequestId(String requestId);

    List<AgentRequestLog> findByUserIdOrderByCreatedAtDesc(String userId);

    List<AgentRequestLog> findByPortalOrderByCreatedAtDesc(String portal);

    @Query(value = "SELECT COALESCE(MAX(id), 0) FROM agent_request_logs WHERE portal = :portal", nativeQuery = true)
    long getMaxSequenceForPortal(@Param("portal") String portal);

    @Query(value = "SELECT COUNT(*) FROM agent_request_logs", nativeQuery = true)
    long getTotalRequestCount();

    @Query(value = "SELECT COUNT(*) FROM agent_request_logs WHERE user_id = :userId", nativeQuery = true)
    long getRequestCountByUser(@Param("userId") String userId);

    @Query(value = "SELECT COUNT(*) FROM agent_request_logs WHERE status = 'SUCCESS'", nativeQuery = true)
    long getSuccessCount();
}
