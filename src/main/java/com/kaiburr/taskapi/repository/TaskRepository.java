package com.kaiburr.taskapi.repository;

import com.kaiburr.taskapi.model.Task;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TaskRepository extends MongoRepository<Task, String> {
    
    // Find tasks by name containing the search string (case-insensitive)
    List<Task> findByNameContainingIgnoreCase(String name);
}