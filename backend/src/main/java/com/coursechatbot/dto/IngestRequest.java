package com.coursechatbot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Request body for POST /api/courses — ingests page texts from JSON. */
@Data
public class IngestRequest {

    @NotBlank(message = "courseId must not be blank")
    private String courseId;

    /**
     * Full PageIndex JSON as produced by build_index.py.
     * Must contain "chapters" (for the index) and "pages" (map of page_number → text).
     */
    @NotBlank(message = "indexJson must not be blank")
    private String indexJson;
}
