package com.coursechatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Stores the hierarchical PageIndex JSON for a course.
 * One row per course, keyed by course_id.
 */
@Table("course_index")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseIndex {

    @Id
    @Column("course_id")
    private String courseId;

    /**
     * The hierarchical index JSON produced by build_index.py.
     * Example structure:
     * {
     *   "title": "Course Title",
     *   "total_pages": 120,
     *   "chapters": [
     *     { "title": "Chapter 1", "summary": "...", "start_page": 1, "end_page": 10, "children": [] }
     *   ]
     * }
     */
    @Column("index_json")
    private String indexJson;

    @Column("title")
    private String title;

    @Column("branch")
    private String branch;

    @Column("subject")
    private String subject;
}
