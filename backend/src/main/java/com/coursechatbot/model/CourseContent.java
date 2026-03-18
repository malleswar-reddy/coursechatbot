package com.coursechatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Stores the text content of each page in a course PDF.
 * Populated by the Python ingest_to_db.py script (or via the /api/courses endpoint).
 */
@Table("course_content")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseContent {

    @Id
    private Long id;

    @Column("course_id")
    private String courseId;

    @Column("page_number")
    private Integer pageNumber;

    private String content;
}
