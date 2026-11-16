package com.reviewhub.repo;

import com.reviewhub.domain.ReviewStatus;
import com.reviewhub.domain.ReviewTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ReviewTaskRepository extends JpaRepository<ReviewTask, Long>, JpaSpecificationExecutor<ReviewTask> {

    List<ReviewTask> findByStatusIn(Collection<ReviewStatus> statuses);

    long countByStatus(ReviewStatus status);

    @Query("select t.status, count(t) from ReviewTask t group by t.status")
    List<Object[]> countGroupByStatus();
}
