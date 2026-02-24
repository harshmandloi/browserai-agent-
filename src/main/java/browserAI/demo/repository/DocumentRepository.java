package browserAI.demo.repository;

import browserAI.demo.model.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByHash(String hash);

    Optional<Document> findByUserIdAndPortalAndReference(String userId, String portal, String reference);

    Optional<Document> findByUserIdAndPortalAndDocumentTypeAndReference(
            String userId, String portal, String documentType, String reference);

    List<Document> findByUserId(String userId);

    List<Document> findByUserIdAndPortal(String userId, String portal);

    boolean existsByHash(String hash);
}
