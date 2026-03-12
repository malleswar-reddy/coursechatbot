package com.coursechatbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Stores the hierarchical PageIndex JSON for a course.
 * One row per course, keyed by course_id.
 */
@Entity
@Table(name = "course_index")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseIndex {

    @Id
    @Column(name = "course_id")
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
    @Column(name = "index_json", columnDefinition = "TEXT", nullable = false)
    private String indexJson;
}
