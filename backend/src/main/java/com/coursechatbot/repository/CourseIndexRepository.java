package com.coursechatbot.repository;

import com.coursechatbot.model.CourseIndex;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface CourseIndexRepository extends ReactiveCrudRepository<CourseIndex, String> {
}
