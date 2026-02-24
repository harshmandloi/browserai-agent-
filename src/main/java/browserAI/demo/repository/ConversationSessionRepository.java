package browserAI.demo.repository;

import browserAI.demo.fsm.ConversationState;
import browserAI.demo.model.entity.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {

    Optional<ConversationSession> findBySessionId(String sessionId);

    List<ConversationSession> findByUserIdAndStateNot(String userId, ConversationState state);

    List<ConversationSession> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<ConversationSession> findByExpiresAtBeforeAndStateNot(LocalDateTime time, ConversationState state);
}
