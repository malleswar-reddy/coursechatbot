package com.coursechatbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Builds system and user prompts for LEARN and EXAM modes, scaled to difficulty level.
 * Also guards against academic integrity violations.
 */
@Service
@Slf4j
public class PromptBuilderService {

    private static final Set<String> INTEGRITY_PHRASES = Set.of(
            "current exam", "ongoing test", "live quiz", "right now in exam",
            "currently in exam", "exam right now", "quiz today", "test today"
    );

    /**
     * Returns non-null if the question violates academic integrity.
     * If violated, callers should return this refusal directly without calling the LLM.
     */
    public String checkIntegrityViolation(String question) {
        String lower = question.toLowerCase();
        for (String phrase : INTEGRITY_PHRASES) {
            if (lower.contains(phrase)) {
                log.warn("Academic integrity guard triggered for question: {}", question);
                return "⚠️ I can't assist with questions during an active exam or quiz. " +
                       "Please come back after your exam — I'll help you review and understand the material thoroughly!";
            }
        }
        return null;
    }

    /**
     * Builds the system prompt for LEARN mode.
     * Adapts depth and vocabulary for the given difficulty level.
     */
    public String buildLearnSystemPrompt(String branch, String difficultyLevel) {
        String subject = branch != null && !branch.isBlank() ? branch : "Engineering";
        String depthGuide = switch (difficultyLevel == null ? "INTERMEDIATE" : difficultyLevel.toUpperCase()) {
            case "BEGINNER"    -> "Use simple language, avoid jargon, relate every concept to everyday examples. Keep explanations short and encouraging.";
            case "ADVANCED"    -> "Assume strong fundamentals. Use precise technical language, include edge cases, complexity analysis, and connections to research or industry use.";
            default            -> "Balance clarity with technical depth. Use standard terminology and one worked example per concept.";
        };

        return """
                You are an expert %s teacher and exam coach.
                For every answer, structure your response as:
                1. Concept   — explain the core idea simply
                2. Formula / Rule — state it precisely (if applicable)
                3. Example   — work through one example step by step
                4. Application — connect to a real exam scenario or problem type
                
                Depth guideline: %s
                
                Be professional, concise, and motivational. Start with "Great question!" when appropriate.
                Use ONLY the provided context. If something is unclear from context, say so honestly.
                """.formatted(subject, depthGuide);
    }

    /**
     * Builds the system prompt for EXAM mode — hints only, no full answers.
     */
    public String buildExamSystemPrompt(String branch, String difficultyLevel) {
        String subject = branch != null && !branch.isBlank() ? branch : "Engineering";
        return """
                You are a strict %s exam coach in EXAM mode.
                IMPORTANT RULES:
                - NEVER give the full answer or solution.
                - Give ONLY a hint — a guiding question or a first step.
                - Ask a leading question that nudges the student toward the solution.
                - If the student is close, say "You're close — think about…" and point to the next step.
                - Remind the student to try it themselves first.
                
                Be encouraging but firm. Keep hints to 2-3 sentences maximum.
                """.formatted(subject);
    }

    /**
     * Returns the appropriate system prompt based on mode and difficulty.
     */
    public String buildSystemPrompt(String mode, String branch, String difficultyLevel) {
        if ("EXAM".equalsIgnoreCase(mode)) {
            return buildExamSystemPrompt(branch, difficultyLevel);
        }
        return buildLearnSystemPrompt(branch, difficultyLevel);
    }

    /**
     * Builds follow-up suggestion chips based on the question asked.
     * Simple heuristic — can be expanded.
     */
    public List<String> buildFollowUpSuggestions(String question, String mode) {
        String q = question.toLowerCase();
        if (q.contains("what is") || q.contains("define") || q.contains("explain")) {
            return List.of("Give me an example", "How is it used in exams?", "What are common mistakes?");
        }
        if (q.contains("difference") || q.contains("compare") || q.contains("vs")) {
            return List.of("Show a comparison table", "Which is better and when?", "Give a real-world example");
        }
        if (q.contains("how") || q.contains("solve") || q.contains("calculate")) {
            return List.of("Show another example", "What's the formula?", "What are common errors?");
        }
        return List.of("Tell me more", "Give an example", "How does this appear in exams?");
    }
}

