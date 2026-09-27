package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.controller.dto.PracticeAnswerRequest;
import com.ithwx.personalknowledgebase.controller.dto.PracticeCreateRequest;
import com.ithwx.personalknowledgebase.controller.dto.PracticeQuestionResponse;
import com.ithwx.personalknowledgebase.controller.dto.PracticeResultResponse;
import com.ithwx.personalknowledgebase.practice.application.PracticeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/practices")
public class PracticeController {

    private final PracticeService practiceService;

    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeQuestionResponse create(
            @Valid @RequestBody PracticeCreateRequest request
    ) {
        return PracticeQuestionResponse.from(
                practiceService.create(request.topic()));
    }

    @PostMapping("/{id}/answer")
    public PracticeResultResponse answer(
            @PathVariable Long id,
            @Valid @RequestBody PracticeAnswerRequest request
    ) {
        return PracticeResultResponse.from(
                practiceService.answer(id, request.answer()));
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeQuestionResponse retry(@PathVariable Long id) {
        return PracticeQuestionResponse.from(practiceService.retry(id));
    }

    @GetMapping("/mistakes")
    public List<PracticeResultResponse> mistakes() {
        return practiceService.mistakes().stream()
                .map(PracticeResultResponse::from)
                .toList();
    }
}
