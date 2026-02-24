package browserAI.demo.repository;

import browserAI.demo.model.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<AuditLog> findByUserIdOrderByCreatedAtDesc(String userId);
}
