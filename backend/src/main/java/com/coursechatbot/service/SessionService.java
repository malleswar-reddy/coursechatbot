package com.coursechatbot.service;

import com.coursechatbot.dto.PerformanceSummaryResponse;
import com.coursechatbot.model.ChatSession;
import com.coursechatbot.model.ExamPerformance;
import com.coursechatbot.repository.ChatSessionRepository;
import com.coursechatbot.repository.ExamPerformanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages study sessions: creation, interaction recording, and performance summaries.
 * Also provides Pomodoro timing: returns pomodoroReminder=true after 25 min of study.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionService {

    private static final long POMODORO_MINUTES = 25;

    private final ChatSessionRepository     sessionRepository;
    private final ExamPerformanceRepository performanceRepository;

    /** Create a new session and persist it. */
    public Mono<ChatSession> createSession(String courseId, String mode, String difficulty) {
        ChatSession session = ChatSession.builder()
                .courseId(courseId)
                .mode(mode != null ? mode.toUpperCase() : "LEARN")
                .difficulty(difficulty != null ? difficulty.toUpperCase() : "INTERMEDIATE")
                .startedAt(OffsetDateTime.now())
                .messageCount(0)
                .hintCount(0)
                .build();
        return sessionRepository.save(session)
                .doOnSuccess(s -> log.info("Created session {} for course={} mode={}", s.getSessionId(), courseId, mode));
    }

    /**
     * Record one interaction and increment counters on the session.
     * responseType: HINT | EXPLAINED | REFUSED
     */
    public Mono<Void> recordInteraction(UUID sessionId, String question, String responseType, String conceptTag) {
        ExamPerformance perf = ExamPerformance.builder()
                .sessionId(sessionId)
                .question(question)
                .responseType(responseType)
                .conceptTag(conceptTag)
                .createdAt(OffsetDateTime.now())
                .build();

        return performanceRepository.save(perf)
                .then(sessionRepository.findById(sessionId))
                .flatMap(session -> {
                    session.setMessageCount(session.getMessageCount() + 1);
                    if ("HINT".equalsIgnoreCase(responseType)) {
                        session.setHintCount(session.getHintCount() + 1);
                    }
                    return sessionRepository.save(session);
                })
                .then();
    }

    /**
     * Returns true if the session has exceeded the Pomodoro focus threshold (25 min).
     */
    public Mono<Boolean> isPomodoroThresholdReached(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .map(session -> Duration.between(session.getStartedAt(), OffsetDateTime.now()).toMinutes() >= POMODORO_MINUTES)
                .defaultIfEmpty(false);
    }

    /** Build the end-of-session performance summary. */
    public Mono<PerformanceSummaryResponse> getPerformanceSummary(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Session not found: " + sessionId)))
                .flatMap(session ->
                        performanceRepository.findBySessionId(sessionId)
                                .collectList()
                                .map(perfs -> {
                                    long minutes = Duration.between(session.getStartedAt(), OffsetDateTime.now()).toMinutes();
                                    List<String> conceptGaps = perfs.stream()
                                            .filter(p -> "HINT".equals(p.getResponseType()) && p.getConceptTag() != null)
                                            .map(ExamPerformance::getConceptTag)
                                            .distinct()
                                            .collect(Collectors.toList());
                                    return PerformanceSummaryResponse.builder()
                                            .sessionId(sessionId.toString())
                                            .questionsAttempted(session.getMessageCount())
                                            .hintCount(session.getHintCount())
                                            .conceptGaps(conceptGaps)
                                            .studyDurationMinutes(minutes)
                                            .build();
                                })
                );
    }
}

