package com.reviewhub.repo;

import com.reviewhub.domain.Finding;
import com.reviewhub.domain.FindingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface FindingRepository extends JpaRepository<Finding, Long> {

    List<Finding> findByTaskIdOrderByFilePathAscLineNoAscIdAsc(Long taskId);

    @Transactional
    void deleteByTaskId(Long taskId);

    long countByStatus(FindingStatus status);

    @Query("select f.severity, count(f) from Finding f group by f.severity")
    List<Object[]> countGroupBySeverity();

    long count();
}
