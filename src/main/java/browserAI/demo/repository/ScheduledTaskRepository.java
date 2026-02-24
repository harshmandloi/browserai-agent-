package browserAI.demo.repository;

import browserAI.demo.model.entity.ScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

    List<ScheduledTask> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ScheduledTask> findByStatus(String status);

    List<ScheduledTask> findByUserIdAndStatus(String userId, String status);

    List<ScheduledTask> findByPortalAndDocumentType(String portal, String documentType);
}
