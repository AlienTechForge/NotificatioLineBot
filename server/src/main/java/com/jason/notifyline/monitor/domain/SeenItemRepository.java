package com.jason.notifyline.monitor.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SeenItemRepository extends JpaRepository<SeenItem, SeenItemId> {
}
