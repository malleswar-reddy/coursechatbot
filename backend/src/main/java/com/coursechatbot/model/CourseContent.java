package com.coursechatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single page of course content — plain POJO (no DB, stored in ChromaDB).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseContent {

    private Long id;
    private String courseId;
    private Integer pageNumber;
    private String content;
}
