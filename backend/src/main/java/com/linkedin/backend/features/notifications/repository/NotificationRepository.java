package com.linkedin.backend.features.notifications.repository;

import com.linkedin.backend.features.authentication.model.User;
import com.linkedin.backend.features.notifications.model.Notification;
import com.linkedin.backend.features.notifications.model.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipient(User recipient);
    List<Notification> findByRecipientOrderByCreationDateDesc(User user);
    List<Notification> findByRecipientAndType(User recipient, NotificationType type);
    List<Notification> findByTypeOrderByCreationDateDesc(NotificationType type);

}

