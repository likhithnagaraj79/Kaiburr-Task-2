package com.kaiburr.taskapi.util;

import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CommandValidator {
    
    // Dangerous commands and patterns
    private static final List<String> DANGEROUS_COMMANDS = Arrays.asList(
        "rm", "rmdir", "del", "format", "mkfs",
        "dd", "fdisk", "parted",
        "kill", "killall", "pkill",
        "shutdown", "reboot", "halt", "poweroff",
        "chmod", "chown", "chgrp",
        "wget", "curl", "nc", "netcat",
        "iptables", "ufw", "firewall-cmd",
        "useradd", "userdel", "passwd",
        "su", "sudo",
        "crontab",
        "systemctl", "service"
    );
    
    // Dangerous patterns
    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
        Pattern.compile(".*[;&|`$].*"),  // Command chaining or injection
        Pattern.compile(".*\\$\\(.*\\).*"),  // Command substitution
        Pattern.compile(".*\\<\\(.*\\).*"),  // Process substitution
        Pattern.compile(".*\\>.*"),  // Redirection
        Pattern.compile(".*\\|.*"),  // Piping (can be dangerous)
        Pattern.compile(".*\\\\.*"),  // Escape sequences
        Pattern.compile(".*\\.\\./.*")  // Directory traversal
    );
    
    public boolean isCommandSafe(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        
        String lowerCommand = command.toLowerCase().trim();
        
        // Check for dangerous commands
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (lowerCommand.startsWith(dangerous + " ") || 
                lowerCommand.equals(dangerous) ||
                lowerCommand.contains(" " + dangerous + " ")) {
                return false;
            }
        }
        
        // Check for dangerous patterns
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).matches()) {
                return false;
            }
        }
        
        return true;
    }
    
    public String getValidationError(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "Command cannot be empty";
        }
        
        if (!isCommandSafe(command)) {
            return "Command contains unsafe or malicious code. " +
                   "Prohibited: dangerous commands (rm, sudo, etc.), " +
                   "command chaining (;, |, &), redirections (>, <), " +
                   "command substitution ($(), ``), and directory traversal (..)";
        }
        
        return null;
    }
}