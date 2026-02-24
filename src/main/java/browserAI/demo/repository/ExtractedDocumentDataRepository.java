package browserAI.demo.repository;

import browserAI.demo.model.entity.ExtractedDocumentData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExtractedDocumentDataRepository extends JpaRepository<ExtractedDocumentData, Long> {

    Optional<ExtractedDocumentData> findByDocumentId(Long documentId);

    List<ExtractedDocumentData> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ExtractedDocumentData> findByUserIdAndPortal(String userId, String portal);

    List<ExtractedDocumentData> findByUserIdAndPortalAndDocumentType(String userId, String portal, String documentType);

    boolean existsByDocumentId(Long documentId);
}
