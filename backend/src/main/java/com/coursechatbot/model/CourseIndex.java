package com.coursechatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a course index entry — plain POJO (no DB, metadata served from ChromaDB).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseIndex {

    private String courseId;
    private String indexJson;
    private String title;
    private String branch;
    private String subject;
}
