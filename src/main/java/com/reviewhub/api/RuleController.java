package com.reviewhub.api;

import com.reviewhub.api.dto.AdminDtos;
import com.reviewhub.domain.ReviewRule;
import com.reviewhub.domain.Severity;
import com.reviewhub.repo.ReviewRuleRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 评审规则管理 API */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final ReviewRuleRepository repo;

    public RuleController(ReviewRuleRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<ReviewRule> list() {
        return repo.findAllByOrderById();
    }

    @PostMapping
    public ReviewRule create(@Valid @RequestBody AdminDtos.RuleRequest req) {
        if (repo.existsByName(req.name().trim())) {
            throw new IllegalArgumentException("规则名已存在: " + req.name());
        }
        ReviewRule r = new ReviewRule();
        apply(r, req);
        r.setBuiltin(false);
        return repo.save(r);
    }

    @PutMapping("/{id}")
    public ReviewRule update(@PathVariable Long id, @Valid @RequestBody AdminDtos.RuleRequest req) {
        ReviewRule r = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("规则不存在: " + id));
        repo.findAll().stream()
                .filter(o -> !o.getId().equals(id) && o.getName().equals(req.name().trim()))
                .findAny()
                .ifPresent(o -> {
                    throw new IllegalArgumentException("规则名已存在: " + req.name());
                });
        apply(r, req);
        return repo.save(r);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        ReviewRule r = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("规则不存在: " + id));
        if (r.isBuiltin()) {
            throw new IllegalArgumentException("内置规则不允许删除，可停用或编辑");
        }
        repo.delete(r);
    }

    private void apply(ReviewRule r, AdminDtos.RuleRequest req) {
        r.setName(req.name().trim());
        r.setDescription(req.description());
        r.setFilePattern(req.filePattern().trim());
        r.setSeverity(Severity.parse(req.severity(), Severity.MAJOR));
        r.setPrompt(req.prompt());
        r.setEnabled(req.enabled() == null || req.enabled());
    }
}
