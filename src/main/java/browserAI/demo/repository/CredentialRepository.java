package browserAI.demo.repository;

import browserAI.demo.model.entity.Credential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CredentialRepository extends JpaRepository<Credential, Long> {

    Optional<Credential> findByUserIdAndPortal(String userId, String portal);

    boolean existsByUserIdAndPortal(String userId, String portal);
}
