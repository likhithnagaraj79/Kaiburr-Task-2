package com.kaiburr.taskapi.controller;

import com.kaiburr.taskapi.model.Task;
import com.kaiburr.taskapi.model.TaskExecution;
import com.kaiburr.taskapi.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/tasks")
@CrossOrigin(origins = "*")
public class TaskController {
    
    @Autowired
    private TaskService taskService;
    
    // GET all tasks or GET task by ID
    @GetMapping
    public ResponseEntity<?> getTasks(@RequestParam(required = false) String id) {
        try {
            if (id != null && !id.trim().isEmpty()) {
                // Get task by ID
                Optional<Task> task = taskService.getTaskById(id);
                if (task.isPresent()) {
                    return ResponseEntity.ok(task.get());
                } else {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Task not found with id: " + id);
                }
            } else {
                // Get all tasks
                List<Task> tasks = taskService.getAllTasks();
                return ResponseEntity.ok(tasks);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error retrieving tasks: " + e.getMessage());
        }
    }
    
    // PUT (create/update) a task
    @PutMapping
    public ResponseEntity<?> createTask(@RequestBody Task task) {
        try {
            Task savedTask = taskService.createTask(task);
            return ResponseEntity.status(HttpStatus.CREATED).body(savedTask);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Validation error: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error creating task: " + e.getMessage());
        }
    }
    
    // DELETE a task
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTask(@PathVariable String id) {
        try {
            Optional<Task> task = taskService.getTaskById(id);
            if (!task.isPresent()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Task not found with id: " + id);
            }
            
            taskService.deleteTask(id);
            return ResponseEntity.ok("Task deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error deleting task: " + e.getMessage());
        }
    }
    
    // GET (find) tasks by name
    @GetMapping("/search")
    public ResponseEntity<?> findTasksByName(@RequestParam String name) {
        try {
            if (name == null || name.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Search name parameter is required");
            }
            
            List<Task> tasks = taskService.findTasksByName(name);
            
            if (tasks.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No tasks found with name containing: " + name);
            }
            
            return ResponseEntity.ok(tasks);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error searching tasks: " + e.getMessage());
        }
    }
    
    // PUT (execute) a task
    @PutMapping("/{id}/execute")
    public ResponseEntity<?> executeTask(@PathVariable String id) {
        try {
            TaskExecution execution = taskService.executeTask(id);
            return ResponseEntity.ok(execution);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error executing task: " + e.getMessage());
        }
    }
}