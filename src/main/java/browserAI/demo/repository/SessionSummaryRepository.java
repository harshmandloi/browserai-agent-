package browserAI.demo.repository;

import browserAI.demo.model.entity.SessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionSummaryRepository extends JpaRepository<SessionSummary, Long> {

    List<SessionSummary> findByPortalOrderByCreatedAtDesc(String portal);

    List<SessionSummary> findByPortalAndDocumentTypeOrderByCreatedAtDesc(String portal, String documentType);

    @Query("SELECT s FROM SessionSummary s WHERE s.portal = :portal AND s.wasSuccessful = true ORDER BY s.createdAt DESC")
    List<SessionSummary> findSuccessfulByPortal(@Param("portal") String portal);

    @Query(value = "SELECT * FROM session_summaries WHERE portal = :portal AND was_successful = true ORDER BY created_at DESC LIMIT :limit",
           nativeQuery = true)
    List<SessionSummary> findRecentSuccessfulByPortal(@Param("portal") String portal, @Param("limit") int limit);
}
