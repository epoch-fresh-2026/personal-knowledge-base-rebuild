package com.ithwx.personalknowledgebase.qa.domain;

import java.util.List;

public record SearchPlan(String resolvedQuestion, List<String> searchTasks) {

    public SearchPlan {
        if (resolvedQuestion == null || resolvedQuestion.isBlank()) {
            throw new IllegalArgumentException("补全后的问题不能为空");
        }
        if (searchTasks == null || searchTasks.isEmpty() || searchTasks.size() > 2
                || searchTasks.stream().anyMatch(task -> task == null || task.isBlank())) {
            throw new IllegalArgumentException("查询规划必须包含一个或两个非空任务");
        }
        resolvedQuestion = resolvedQuestion.strip();
        searchTasks = searchTasks.stream().map(String::strip).distinct().toList();
    }
}
