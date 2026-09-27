package com.ithwx.personalknowledgebase.practice.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataPracticeRepository extends JpaRepository<PracticeEntity, Long> {

    List<PracticeEntity> findAllByNeedsReviewTrueAndRetryOfIdIsNullOrderByCreatedAtDesc();
}
