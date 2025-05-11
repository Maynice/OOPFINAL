package com.example;

import java.awt.BorderLayout;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamDiscoveryEvent;
import com.github.sarxos.webcam.WebcamDiscoveryListener;
import com.github.sarxos.webcam.WebcamEvent;
import com.github.sarxos.webcam.WebcamListener;
import com.github.sarxos.webcam.WebcamPanel;
import com.github.sarxos.webcam.WebcamPicker;
import com.github.sarxos.webcam.WebcamResolution;

public class WebcamCurrencyCounter extends JFrame
        implements Runnable, WebcamListener, WindowListener, ItemListener, WebcamDiscoveryListener {

    private static final long serialVersionUID = 1L;
    private static final String API_KEY = "AIzaSyCfBczzxEVD9H-62V5yWuDNbP4p3h26Tp8"; // Replace with your Gemini API key
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1/models/gemini-1.5-flash:generateContent?key="
            + API_KEY;

    private Webcam webcam = null;
    private WebcamPanel panel = null;
    private WebcamPicker picker = null;
    private JLabel statusLabel;
    private File lastCapturedImage = null;
    private Model model;
    private Recognizer recognizer;
    private ExecutorService executor;
    private boolean isListening = false;
    private final String MODEL_PATH = "model"; // Path to Vosk model folder
    private TargetDataLine line;
    private boolean isProcessing = false;

    @Override
    public void run() {
        Webcam.addDiscoveryListener(this);

        setTitle("IDR Bills Counter (Voice Enabled)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        addWindowListener(this);

        // Webcam Picker (Dropdown for multiple webcams)
        picker = new WebcamPicker();
        picker.addItemListener(this);

        // Status label for voice recognition and processing status
        statusLabel = new JLabel("Voice Recognition Status: Initializing...");

        // Select default webcam
        webcam = picker.getSelectedWebcam();
        if (webcam == null) {
            System.out.println("No webcams found...");
            System.exit(1);
        }

        webcam.setViewSize(WebcamResolution.VGA.getSize());
        webcam.addWebcamListener(this);

        // Create webcam panel (always visible for debugging purposes)
        panel = new WebcamPanel(webcam, false);
        panel.setFPSDisplayed(true);

        // Add components to frame - simplified UI without buttons
        add(picker, BorderLayout.NORTH);
        add(panel, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        pack();
        setVisible(true);

        // Initialize voice recognition in a separate thread
        executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> {
            try {
                initializeVoiceRecognition();
            } catch (Exception e) {
                e.printStackTrace();
                updateStatus("Voice recognition initialization failed: " + e.getMessage());
                // Provide audio feedback about the failure
                textToSpeech("Voice recognition initialization failed. Please restart the application.");
            }
        });

        // Auto-open the webcam on startup
        executor.submit(() -> {
            try {
                Thread.sleep(2000); // Give time for initialization
                openCamera();
                textToSpeech(
                        "Ready to analyze.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void initializeVoiceRecognition() {
        try {
            updateStatus("Initializing voice recognition...");

            // Check if model exists
            if (!Files.exists(Paths.get(MODEL_PATH))) {
                updateStatus("Voice model not found");
                textToSpeech(
                        "Voice recognition model not found. Please download a small model from Vosk website and extract it to a folder named 'model'.");
                return;
            }

            // Configure Vosk
            LibVosk.setLogLevel(LogLevel.INFO);
            model = new Model(MODEL_PATH);

            // Configure audio
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                updateStatus("Microphone not supported");
                textToSpeech("Microphone is not supported on this device. Please connect a microphone.");
                return;
            }

            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);

            // Create recognizer
            recognizer = new Recognizer(model, 16000);

            updateStatus("Voice recognition ready. Say 'camera open' or 'take picture'");

            // Start listening in a separate thread
            startVoiceRecognition();

        } catch (Exception e) {
            e.printStackTrace();
            updateStatus("Voice recognition failed: " + e.getMessage());
            textToSpeech("Voice recognition failed to initialize. Please restart the application.");
        }
    }

    private void startVoiceRecognition() {
        isListening = true;

        executor.submit(() -> {
            try {
                line.start();
                byte[] buffer = new byte[4096];

                while (isListening) {
                    int bytesRead = line.read(buffer, 0, buffer.length);

                    if (bytesRead > 0) {
                        // Process audio data
                        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                            // We have a final result
                            String result = recognizer.getResult();
                            processVoiceResult(result);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                textToSpeech("Voice recognition error. Please restart the application.");
            } finally {
                if (line != null) {
                    line.stop();
                    line.close();
                }

                // Get the final result
                if (recognizer != null) {
                    String finalResult = recognizer.getFinalResult();
                    processVoiceResult(finalResult);
                }
            }
        });
    }

    private void processVoiceResult(String jsonResult) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode resultNode = mapper.readTree(jsonResult);
            String text = resultNode.get("text").asText().toLowerCase().trim();

            System.out.println("Recognized text: '" + text + "'");

            if (!text.isEmpty()) {
                // More explicit matching for commands
                final String recognizedText = text;

                SwingUtilities.invokeLater(() -> {
                    updateStatus("Recognized: '" + recognizedText + "'");

                    // Only process commands if not already processing an image
                    if (!isProcessing) {
                        if (recognizedText.contains("camera") && recognizedText.contains("open")) {
                            System.out.println("COMMAND DETECTED: Camera Open");
                            updateStatus("Command executed: 'camera open'");
                            openCamera();
                            textToSpeech("Ready to analyze.");
                        } else if ((recognizedText.contains("take") && recognizedText.contains("picture")) ||
                                (recognizedText.contains("capture") && recognizedText.contains("image"))) {
                            System.out.println("COMMAND DETECTED: Take Picture");
                            updateStatus("Command executed: 'take picture'");
                            captureAndAnalyze();
                        }
                    } else {
                        textToSpeech("Please wait, still processing the previous request.");
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("Error processing voice result: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: " + message);
        });
    }

    private void openCamera() {
        if (!webcam.isOpen()) {
            webcam.open();
            panel.setVisible(true);
            panel.start();
            pack();
            updateStatus("Camera not Open");
        } else {
            updateStatus("Camera is already open");
        }
    }

    private void captureAndAnalyze() {
        if (isProcessing) {
            textToSpeech("Already processing an image. Please wait.");
            return;
        }

        isProcessing = true;
        textToSpeech("Taking picture and analyzing currency. Please wait.");

        executor.submit(() -> {
            try {
                captureImage();
                if (lastCapturedImage != null) {
                    analyzeCurrency();
                }
            } finally {
                isProcessing = false;
            }
        });
    }

    private void captureImage() {
        if (webcam.isOpen()) {
            BufferedImage image = webcam.getImage();
            try {
                File outputfile = new File("captured_image.png");
                ImageIO.write(image, "PNG", outputfile);
                lastCapturedImage = outputfile;
                updateStatus("Image captured. Analyzing...");
            } catch (IOException e) {
                e.printStackTrace();
                updateStatus("Error saving image");
                textToSpeech("Error saving image. Please try again.");
                isProcessing = false;
            }
        } else {
            updateStatus("Camera not open. Say 'camera open' first");
            textToSpeech("Camera is not open. Please say 'camera open' first.");
            isProcessing = false;
        }
    }

    private void analyzeCurrency() {
        if (lastCapturedImage != null && lastCapturedImage.exists()) {
            try {
                // Read image bytes
                byte[] imageBytes = Files.readAllBytes(lastCapturedImage.toPath());

                // Process the image with Gemini
                String jsonResponse = analyzeImageWithGemini(imageBytes);

                // Parse the response and speak it
                String textToSpeakResult = parseAndDisplayResults(jsonResponse);

                // Speak the results - no UI dialog needed
                textToSpeech(textToSpeakResult);
                updateStatus("Analysis complete. Ready for next command");

            } catch (Exception e) {
                e.printStackTrace();
                updateStatus("Error during analysis");
                textToSpeech("Error analyzing image. Please try again.");
            }
        } else {
            updateStatus("No image to analyze");
            textToSpeech("No image captured or image file missing. Please try again.");
        }
    }

    private String analyzeImageWithGemini(byte[] imageBytes) throws IOException {
        URL url = new URL(GEMINI_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String requestBody = "{ \"contents\": [ { \"parts\": [ { \"text\": \"Analyze the image and provide the count of each Indonesian Rupiah bill and the total amount in the following format: \\n\\n[Count]x [Denomination] IDR bills\\n[Count]x [Denomination] IDR bills\\n...\\nTotal Amount: [Total Amount] IDR\" }, { \"inline_data\": { \"mime_type\": \"image/jpeg\", \"data\": \""
                + java.util.Base64.getEncoder().encodeToString(imageBytes) + "\" } } ] } ] }";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes());
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private String parseAndDisplayResults(String jsonResponse) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(jsonResponse);
        JsonNode textNode = rootNode.at("/candidates/0/content/parts/0/text");

        if (textNode.isTextual()) {
            String aiResponseText = textNode.asText();
            System.out.println(aiResponseText);
            return formatTextForSpeech(aiResponseText); // Format the text
        } else {
            System.out.println("Error: Could not extract text from API response.");
            return "Error processing results. No bills detected or API error.";
        }
    }

    private String formatTextForSpeech(String aiResponseText) {
        StringBuilder formattedText = new StringBuilder("I found the following: ");
        Pattern billPattern = Pattern.compile("(\\d+)x (\\d+) IDR bill(s?)");
        Matcher billMatcher = billPattern.matcher(aiResponseText);
        int totalAmount = 0;
        boolean billFound = false;

        while (billMatcher.find()) {
            int count = Integer.parseInt(billMatcher.group(1));
            int denomination = Integer.parseInt(billMatcher.group(2));
            totalAmount += count * denomination;

            // Format in a way that's clear when spoken
            if (count == 1) {
                formattedText.append(" ").append(count).append(" ").append(denomination).append(" rupiah bill. ");
            } else {
                formattedText.append(" ").append(count).append(" ").append(denomination).append(" rupiah bills. ");
            }
            billFound = true;
        }

        Pattern totalPattern = Pattern.compile("Total Amount: (\\d+) IDR");
        Matcher totalMatcher = totalPattern.matcher(aiResponseText);

        boolean totalFound = totalMatcher.find();
        if (totalFound) {
            totalAmount = Integer.parseInt(totalMatcher.group(1));
        }

        if (!billFound && totalFound) {
            formattedText.append("Total amount is ").append(totalAmount).append(" rupiah.");
        } else if (billFound) {
            formattedText.append("The total amount is ").append(totalAmount).append(" rupiah.");
        } else {
            if (aiResponseText.toLowerCase().contains("no bills") ||
                    aiResponseText.toLowerCase().contains("unable to detect") ||
                    aiResponseText.toLowerCase().contains("cannot identify")) {
                formattedText = new StringBuilder(
                        "No bills detected in the image. Please try again with better lighting or positioning.");
            } else {
                formattedText = new StringBuilder("Could not process the image. Please try again.");
            }
        }

        // Add instruction for what to do next
        formattedText.append(" Say 'take picture' to capture another image.");

        return formattedText.toString();
    }

    private void textToSpeech(String text) {
        try {
            // Clean up the text for TTS by escaping special characters
            String cleanText = text.replace("'", "''")
                    .replace("\"", "")
                    .replace("\n", " ");

            String command = "powershell -Command \"Add-Type -AssemblyName System.Speech; $speak = New-Object System.Speech.Synthesis.SpeechSynthesizer; $speak.Speak('"
                    + cleanText + "');\"";

            Runtime.getRuntime().exec(command);

            // Give time for TTS to start before returning
            Thread.sleep(100);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error with text-to-speech: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new WebcamCurrencyCounter());
    }

    // Required interface implementations
    @Override
    public void webcamOpen(WebcamEvent we) {
        System.out.println("Webcam Opened");
    }

    @Override
    public void webcamClosed(WebcamEvent we) {
        System.out.println("Webcam Closed");
    }

    @Override
    public void webcamDisposed(WebcamEvent we) {
        System.out.println("Webcam Disposed");
    }

    @Override
    public void webcamImageObtained(WebcamEvent we) {
    }

    @Override
    public void windowActivated(WindowEvent e) {
    }

    @Override
    public void windowClosed(WindowEvent e) {
        webcam.close();
        // Shutdown the voice recognition
        isListening = false;
        if (recognizer != null) {
            recognizer.close();
        }
        if (model != null) {
            model.close();
        }
        if (line != null) {
            line.close();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public void windowClosing(WindowEvent e) {
    }

    @Override
    public void windowOpened(WindowEvent e) {
    }

    @Override
    public void windowDeactivated(WindowEvent e) {
    }

    @Override
    public void windowDeiconified(WindowEvent e) {
        if (panel != null) {
            panel.resume();
        }
    }

    @Override
    public void windowIconified(WindowEvent e) {
        if (panel != null) {
            panel.pause();
        }
    }

    @Override
    public void itemStateChanged(ItemEvent e) {
        if (e.getItem() != webcam) {
            if (webcam != null) {
                panel.stop();
                remove(panel);
                webcam.removeWebcamListener(this);
                webcam.close();

                // Switch to selected webcam
                webcam = (Webcam) e.getItem();
                webcam.setViewSize(WebcamResolution.VGA.getSize());
                webcam.addWebcamListener(this);

                panel = new WebcamPanel(webcam, false);
                panel.setFPSDisplayed(true);
                panel.setVisible(webcam.isOpen());

                add(panel, BorderLayout.CENTER);
                pack();

                if (webcam.isOpen()) {
                    Thread t = new Thread(() -> panel.start());
                    t.setName("Webcam-Switcher");
                    t.setDaemon(true);
                    t.start();
                }

                textToSpeech("Webcam switched. Say 'take picture' when ready.");
            }
        }
    }

    @Override
    public void webcamFound(WebcamDiscoveryEvent event) {
        if (picker != null) {
            picker.addItem(event.getWebcam());
            textToSpeech("New webcam detected.");
        }
    }

    @Override
    public void webcamGone(WebcamDiscoveryEvent event) {
        if (picker != null) {
            picker.removeItem(event.getWebcam());
            textToSpeech("A webcam has been disconnected.");
        }
    }
}