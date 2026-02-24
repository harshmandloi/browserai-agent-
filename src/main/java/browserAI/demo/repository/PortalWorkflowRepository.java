package browserAI.demo.repository;

import browserAI.demo.model.entity.PortalWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PortalWorkflowRepository extends JpaRepository<PortalWorkflow, Long> {

    Optional<PortalWorkflow> findByPortalAndDocumentType(String portal, String documentType);

    List<PortalWorkflow> findByPortal(String portal);

    @Query("SELECT pw FROM PortalWorkflow pw WHERE pw.portal = :portal AND pw.isVerified = true ORDER BY pw.successCount DESC")
    List<PortalWorkflow> findVerifiedByPortal(@Param("portal") String portal);

    @Query("SELECT pw FROM PortalWorkflow pw WHERE pw.isVerified = true ORDER BY pw.successCount DESC")
    List<PortalWorkflow> findAllVerified();
}
