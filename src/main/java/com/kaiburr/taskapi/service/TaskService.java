package com.kaiburr.taskapi.service;

import com.kaiburr.taskapi.model.Task;
import com.kaiburr.taskapi.model.TaskExecution;
import com.kaiburr.taskapi.repository.TaskRepository;
import com.kaiburr.taskapi.util.CommandValidator;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskService {
    
    private static final Logger logger = LoggerFactory.getLogger(TaskService.class);
    
    @Autowired
    private TaskRepository taskRepository;
    
    @Autowired
    private CommandValidator commandValidator;
    
    @Value("${kubernetes.namespace:default}")
    private String kubernetesNamespace;
    
    private CoreV1Api coreV1Api;
    
    public TaskService() {
        try {
            // Initialize Kubernetes client
            ApiClient client = Config.defaultClient();
            Configuration.setDefaultApiClient(client);
            this.coreV1Api = new CoreV1Api();
            logger.info("Kubernetes client initialized successfully");
        } catch (Exception e) {
            logger.warn("Failed to initialize Kubernetes client. Will use local execution: {}", e.getMessage());
            this.coreV1Api = null;
        }
    }
    
    public List<Task> getAllTasks() {
        return taskRepository.findAll();
    }
    
    public Optional<Task> getTaskById(String id) {
        return taskRepository.findById(id);
    }
    
    public Task createTask(Task task) throws IllegalArgumentException {
        // Validate command
        String validationError = commandValidator.getValidationError(task.getCommand());
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }
        
        // Validate required fields
        if (task.getName() == null || task.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Task name is required");
        }
        if (task.getOwner() == null || task.getOwner().trim().isEmpty()) {
            throw new IllegalArgumentException("Task owner is required");
        }
        
        // Initialize taskExecutions if null
        if (task.getTaskExecutions() == null) {
            task.setTaskExecutions(new java.util.ArrayList<>());
        }
        
        return taskRepository.save(task);
    }
    
    public void deleteTask(String id) {
        taskRepository.deleteById(id);
    }
    
    public List<Task> findTasksByName(String name) {
        return taskRepository.findByNameContainingIgnoreCase(name);
    }
    
    public TaskExecution executeTask(String taskId) throws Exception {
        Optional<Task> taskOpt = taskRepository.findById(taskId);
        
        if (!taskOpt.isPresent()) {
            throw new IllegalArgumentException("Task not found with id: " + taskId);
        }
        
        Task task = taskOpt.get();
        
        // Check if running in Kubernetes environment
        if (coreV1Api != null) {
            return executeTaskInKubernetesPod(task);
        } else {
            return executeTaskLocally(task);
        }
    }
    
    /**
     * Execute task in a Kubernetes pod using busybox image
     */
    private TaskExecution executeTaskInKubernetesPod(Task task) throws Exception {
        TaskExecution execution = new TaskExecution();
        execution.setStartTime(new Date());
        
        String podName = "task-exec-" + task.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        try {
            logger.info("Creating Kubernetes pod: {} for task: {}", podName, task.getId());
            
            // Create pod specification
            V1Pod pod = new V1PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .addToLabels("app", "task-executor")
                    .addToLabels("task-id", task.getId())
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .addNewContainer()
                        .withName("task-container")
                        .withImage("busybox:latest")
                        .withCommand("sh", "-c", task.getCommand())
                    .endContainer()
                .endSpec()
                .build();
            
            // Create the pod
            V1Pod createdPod = coreV1Api.createNamespacedPod(kubernetesNamespace, pod, null, null, null, null);
            logger.info("Pod created successfully: {}", createdPod.getMetadata().getName());
            
            // Wait for pod to complete (with timeout)
            String output = waitForPodCompletionAndGetLogs(podName);
            
            execution.setEndTime(new Date());
            execution.setOutput(output);
            
            // Delete the pod after execution
            deletePod(podName);
            
        } catch (ApiException e) {
            logger.error("Kubernetes API error: {}", e.getResponseBody());
            execution.setEndTime(new Date());
            execution.setOutput("Kubernetes execution failed: " + e.getMessage());
            throw new Exception("Failed to execute task in Kubernetes: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Execution error: {}", e.getMessage());
            execution.setEndTime(new Date());
            execution.setOutput("Execution failed: " + e.getMessage());
            throw e;
        }
        
        // Save execution to task
        task.addTaskExecution(execution);
        taskRepository.save(task);
        
        return execution;
    }
    
    /**
     * Wait for pod to complete and retrieve logs
     */
    private String waitForPodCompletionAndGetLogs(String podName) throws Exception {
        int maxAttempts = 60; // 60 seconds timeout
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                V1Pod pod = coreV1Api.readNamespacedPod(podName, kubernetesNamespace, null);
                V1PodStatus status = pod.getStatus();
                
                if (status != null && status.getPhase() != null) {
                    String phase = status.getPhase();
                    
                    if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                        // Pod completed, get logs
                        String logs = coreV1Api.readNamespacedPodLog(
                            podName,
                            kubernetesNamespace,
                            null,  // container name
                            false, // follow
                            null,  // insecureSkipTLSVerifyBackend
                            null,  // limitBytes
                            "false", // pretty
                            false, // previous
                            null,  // sinceSeconds
                            null,  // tailLines
                            false  // timestamps
                        );
                        
                        if (logs == null || logs.trim().isEmpty()) {
                            return "Command executed (no output)";
                        }
                        return logs;
                    }
                }
                
                Thread.sleep(1000); // Wait 1 second before checking again
                attempt++;
                
            } catch (ApiException e) {
                logger.error("Error checking pod status: {}", e.getMessage());
                throw new Exception("Failed to check pod status: " + e.getMessage());
            }
        }
        
        throw new Exception("Pod execution timeout (60 seconds)");
    }
    
    /**
     * Delete pod after execution
     */
    private void deletePod(String podName) {
        try {
            coreV1Api.deleteNamespacedPod(
                podName,
                kubernetesNamespace,
                null, // pretty
                null, // dryRun
                null, // gracePeriodSeconds
                null, // orphanDependents
                null, // propagationPolicy
                null  // body
            );
            logger.info("Pod deleted: {}", podName);
        } catch (ApiException e) {
            logger.warn("Failed to delete pod {}: {}", podName, e.getMessage());
        }
    }
    
    /**
     * Fallback: Execute task locally (for development/testing)
     */
    private TaskExecution executeTaskLocally(Task task) throws Exception {
        logger.info("Executing task locally (Kubernetes not available)");
        
        TaskExecution execution = new TaskExecution();
        execution.setStartTime(new Date());
        
        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command("sh", "-c", task.getCommand());
            
            Process process = processBuilder.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            StringBuilder output = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream())
            );
            StringBuilder errorOutput = new StringBuilder();
            
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
            
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroy();
                throw new Exception("Command execution timeout (30 seconds)");
            }
            
            String finalOutput = output.toString();
            if (errorOutput.length() > 0) {
                finalOutput += "\nError: " + errorOutput.toString();
            }
            if (finalOutput.isEmpty()) {
                finalOutput = "Command executed successfully (no output)";
            }
            
            execution.setOutput(finalOutput.trim());
            execution.setEndTime(new Date());
            
            task.addTaskExecution(execution);
            taskRepository.save(task);
            
            return execution;
            
        } catch (Exception e) {
            execution.setEndTime(new Date());
            execution.setOutput("Execution failed: " + e.getMessage());
            task.addTaskExecution(execution);
            taskRepository.save(task);
            throw e;
        }
    }
}