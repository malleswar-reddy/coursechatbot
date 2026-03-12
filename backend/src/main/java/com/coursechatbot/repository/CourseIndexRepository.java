package com.coursechatbot.repository;

import com.coursechatbot.model.CourseIndex;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseIndexRepository extends JpaRepository<CourseIndex, String> {
}
