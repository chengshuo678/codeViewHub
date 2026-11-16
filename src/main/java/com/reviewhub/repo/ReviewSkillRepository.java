package com.reviewhub.repo;

import com.reviewhub.domain.ReviewSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewSkillRepository extends JpaRepository<ReviewSkill, Long> {

    List<ReviewSkill> findAllByOrderById();

    Optional<ReviewSkill> findByDefaultedTrueAndEnabledTrue();

    Optional<ReviewSkill> findFirstByEnabledTrueOrderById();

    boolean existsByName(String name);
}
