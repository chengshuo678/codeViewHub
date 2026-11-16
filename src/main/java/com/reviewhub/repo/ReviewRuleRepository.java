package com.reviewhub.repo;

import com.reviewhub.domain.ReviewRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRuleRepository extends JpaRepository<ReviewRule, Long> {

    List<ReviewRule> findByEnabledTrueOrderById();

    List<ReviewRule> findAllByOrderById();

    boolean existsByName(String name);
}
