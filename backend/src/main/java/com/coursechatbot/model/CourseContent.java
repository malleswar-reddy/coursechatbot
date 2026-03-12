package com.coursechatbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stores the text content of each page in a course PDF.
 * Populated by the Python ingest_to_db.py script (or via the /api/courses endpoint).
 */
@Entity
@Table(
    name = "course_content",
    uniqueConstraints = @UniqueConstraint(columnNames = {"course_id", "page_number"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private String courseId;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(columnDefinition = "TEXT")
    private String content;
}
