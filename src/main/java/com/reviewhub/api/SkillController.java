package com.reviewhub.api;

import com.reviewhub.api.dto.AdminDtos;
import com.reviewhub.domain.ReviewSkill;
import com.reviewhub.repo.ReviewSkillRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 评审技能（智能体人格）管理 API */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final ReviewSkillRepository repo;

    public SkillController(ReviewSkillRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<ReviewSkill> list() {
        return repo.findAllByOrderById();
    }

    @PostMapping
    public ReviewSkill create(@Valid @RequestBody AdminDtos.SkillRequest req) {
        if (repo.existsByName(req.name().trim())) {
            throw new IllegalArgumentException("技能名已存在: " + req.name());
        }
        ReviewSkill s = new ReviewSkill();
        apply(s, req);
        s.setBuiltin(false);
        s.setDefaulted(false);
        return repo.save(s);
    }

    @PutMapping("/{id}")
    public ReviewSkill update(@PathVariable Long id, @Valid @RequestBody AdminDtos.SkillRequest req) {
        ReviewSkill s = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("技能不存在: " + id));
        repo.findAll().stream()
                .filter(o -> !o.getId().equals(id) && o.getName().equals(req.name().trim()))
                .findAny()
                .ifPresent(o -> {
                    throw new IllegalArgumentException("技能名已存在: " + req.name());
                });
        apply(s, req);
        return repo.save(s);
    }

    /** 设为默认技能（每次评审使用） */
    @PutMapping("/{id}/default")
    @Transactional
    public void setDefault(@PathVariable Long id) {
        ReviewSkill target = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("技能不存在: " + id));
        if (!target.isEnabled()) {
            throw new IllegalArgumentException("技能已停用，无法设为默认");
        }
        for (ReviewSkill s : repo.findAllByOrderById()) {
            boolean d = s.getId().equals(id);
            if (s.isDefaulted() != d) {
                s.setDefaulted(d);
                repo.save(s);
            }
        }
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        ReviewSkill s = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("技能不存在: " + id));
        if (s.isBuiltin()) {
            throw new IllegalArgumentException("内置技能不允许删除，可停用或编辑");
        }
        if (s.isDefaulted()) {
            throw new IllegalArgumentException("默认技能不允许删除，请先将其他技能设为默认");
        }
        repo.delete(s);
    }

    private void apply(ReviewSkill s, AdminDtos.SkillRequest req) {
        s.setName(req.name().trim());
        s.setDescription(req.description());
        s.setSystemPrompt(req.systemPrompt());
        s.setEnabled(req.enabled() == null || req.enabled());
    }
}
