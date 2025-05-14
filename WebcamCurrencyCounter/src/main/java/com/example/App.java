package com.example;

import spark.Spark;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Paths;

public class App {
    private static Process cameraProcess = null;
    private static RandomAccessFile lockFile = null;
    private static FileLock lock = null;
    private static final String LOCK_FILE_PATH = "camera_lock.file";
    private static final String PID_FILE_PATH = "camera_pid.txt";
    private static boolean isCameraRunning = false;

    public static void main(String[] args) {
        Spark.port(4567);

        // Enable CORS
        Spark.before((request, response) -> {
            response.header("Access-Control-Allow-Origin", "*");
            response.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.header("Access-Control-Allow-Headers",
                    "Content-Type, Authorization, X-Requested-With, Content-Length, Accept, Origin");
        });

        Spark.options("/*", (request, response) -> {
            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }

            return "OK";
        });

        // Check if camera was previously running but server was restarted
        checkExistingCameraProcess();

        Spark.post("/start-camera", (req, res) -> {
            System.out.println("Received request to start camera");

            // Check if cameraProcess has died but flag wasn't reset
            if (cameraProcess != null && !cameraProcess.isAlive()) {
                System.out.println("Detected dead camera process. Cleaning up...");
                cleanupResources();
            }

            // Check if camera is already running
            if (isCameraRunning) {
                System.out.println("Camera is already running, returning message");
                return "Camera is already running!";
            }

            // Try to acquire file lock
            if (!acquireLock()) {
                System.out.println("Failed to acquire lock, another instance may be running");
                return "Camera is already running!";
            }

            try {
                System.out.println("Starting camera process...");

                // Start camera process
                cameraProcess = new ProcessBuilder(
                        "java",
                        "-jar",
                        "C:\\xampp\\htdocs\\WebcamAI_Test\\WebcamCurrencyCounter\\target\\webcam-currency-counter-1.0-SNAPSHOT.jar")
                        .inheritIO().start();

                // Save PID for future reference
                if (cameraProcess != null && cameraProcess.isAlive()) {
                    savePid(cameraProcess.pid());
                    isCameraRunning = true;

                    // Monitor the process in the background
                    new Thread(() -> {
                        try {
                            cameraProcess.waitFor(); // Wait until camera exits
                            System.out.println("Camera process ended. Cleaning up...");
                            cleanupResources(); // Reset everything when it ends
                        } catch (InterruptedException e) {
                            System.err.println("Camera watcher interrupted: " + e.getMessage());
                        }
                    }).start();

                    return "Camera launched!";
                } else {
                    cleanupResources();
                    return "Failed to start camera process";
                }
            } catch (Exception e) {
                cleanupResources();
                res.status(500);
                return "Error launching camera: " + e.getMessage();
            }
        });

        Spark.post("/stop-camera", (req, res) -> {
            System.out.println("Received request to stop camera");
            if (cleanupResources()) {
                return "Camera stopped successfully!";
            } else {
                return "No camera was running!";
            }
        });

        System.out.println("Server started on port 4567");
    }

    private static boolean acquireLock() {
        try {
            File file = new File(LOCK_FILE_PATH);
            if (!file.exists()) {
                file.createNewFile();
            }

            lockFile = new RandomAccessFile(file, "rw");
            lock = lockFile.getChannel().tryLock();

            if (lock == null) {
                System.out.println("Lock file exists but lock not acquired. Checking if PID is stale...");
                return checkAndCleanupStaleLock(); // Check if we can safely remove stale lock
            }

            return true;
        } catch (Exception e) {
            System.err.println("Error acquiring lock: " + e.getMessage());
            return false;
        }
    }

    private static boolean checkAndCleanupStaleLock() {
        File pidFile = new File(PID_FILE_PATH);
        if (pidFile.exists()) {
            try {
                String pidString = new String(Files.readAllBytes(pidFile.toPath())).trim();
                long pid = Long.parseLong(pidString);
                ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                if (handle != null && handle.isAlive()) {
                    System.out.println("Another camera process is alive with PID: " + pid);
                    return false;
                } else {
                    System.out.println("Found stale PID. Cleaning up...");
                    cleanupResources(); // Dead process — clean up!
                    return acquireLock(); // Try again after cleanup
                }
            } catch (Exception e) {
                System.err.println("Error reading PID: " + e.getMessage());
            }
        }
        return false;
    }

    private static void savePid(long pid) {
        try {
            Files.write(Paths.get(PID_FILE_PATH), String.valueOf(pid).getBytes());
        } catch (IOException e) {
            System.err.println("Error saving PID: " + e.getMessage());
        }
    }

    private static void checkExistingCameraProcess() {
        // Check if PID file exists
        File pidFile = new File(PID_FILE_PATH);
        if (pidFile.exists()) {
            try {
                // Read PID from file
                String pidString = new String(Files.readAllBytes(pidFile.toPath())).trim();
                long pid = Long.parseLong(pidString);

                // Check if process with this PID is running
                ProcessHandle processHandle = ProcessHandle.of(pid).orElse(null);
                if (processHandle != null && processHandle.isAlive()) {
                    // Process is still running
                    isCameraRunning = true;
                    System.out.println("Found existing camera process with PID: " + pid);
                } else {
                    // Process is not running, clean up
                    cleanupResources();
                }
            } catch (Exception e) {
                System.err.println("Error checking existing process: " + e.getMessage());
                cleanupResources();
            }
        }
    }

    private static boolean cleanupResources() {
        boolean wasRunning = isCameraRunning;

        // Destroy process if it exists
        if (cameraProcess != null) {
            System.out.println("Destroying camera process");
            cameraProcess.destroy();
            cameraProcess = null;
        }

        // Release file lock
        try {
            if (lock != null) {
                lock.release();
                lock = null;
            }
            if (lockFile != null) {
                lockFile.close();
                lockFile = null;
            }
        } catch (IOException e) {
            System.err.println("Error releasing lock: " + e.getMessage());
        }

        // Delete lock file and PID file
        try {
            Files.deleteIfExists(Paths.get(LOCK_FILE_PATH));
            Files.deleteIfExists(Paths.get(PID_FILE_PATH));
        } catch (IOException e) {
            System.err.println("Error deleting lock/PID files: " + e.getMessage());
        }

        isCameraRunning = false;
        System.out.println("Resources cleaned up");
        return wasRunning;
    }
}