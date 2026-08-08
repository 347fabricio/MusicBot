package com.jagrosh.jmusicbot.utils;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PythonScriptManager
{
    private static final Logger LOG = LoggerFactory.getLogger(PythonScriptManager.class);

    private static final String SCRIPT_RESOURCE_PATH = "/python/scrapper.py";
    private static File scriptFile;

    /**
     * Ensures scrapper.py is extracted to the working directory and up-to-date.
     * 
     * @return true if the script is extracted and ready; false if extraction failed
     */
    public static synchronized boolean initScript() 
    {
        try (InputStream resourceStream = PythonScriptManager.class.getResourceAsStream(SCRIPT_RESOURCE_PATH)) 
        {
            if (resourceStream == null) 
            {
                LOG.error("Could not find [{}] inside JAR resources!", SCRIPT_RESOURCE_PATH);
                return false;
            }

            byte[] resourceBytes = resourceStream.readAllBytes();
            String resourceHash = calculateSha256(resourceBytes);

            File targetFile = new File(System.getProperty("user.dir"), "scrapper.py");

            if (targetFile.exists()) 
            {
                byte[] localBytes = Files.readAllBytes(targetFile.toPath());
                String localHash = calculateSha256(localBytes);

                if (resourceHash.equalsIgnoreCase(localHash)) 
                {
                    LOG.debug("scrapper.py is up to date (SHA-256 match).");
                    scriptFile = targetFile;
                    return true;
                }
                LOG.info("scrapper.py version mismatch detected. Updating local copy from JAR...");
            } 
            else 
            {
                LOG.info("Extracting scrapper.py from JAR to [{}]", targetFile.getAbsolutePath());
            }

            Files.write(targetFile.toPath(), resourceBytes, 
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.TRUNCATE_EXISTING);

            scriptFile = targetFile;
            return true;
        } 
        catch (Exception e) 
        {
            LOG.error("Failed to extract scrapper.py from JAR: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Gets the extracted script file, initializing it if necessary.
     * 
     * @return File object pointing to scrapper.py, or null if initialization failed.
     */
    public static File getScriptFile()
    {
        if (scriptFile == null && !initScript())
        {
            return null;
        }
        return scriptFile;
    }

    private static String calculateSha256(byte[] data) throws NoSuchAlgorithmException 
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) 
        {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) 
            {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    /**
     * Resolves the Python executable path using the following priority:
     * <ol>
     *   <li>Custom path specified via configuration/system property (if set)</li>
     *   <li>Local virtual environment binary based on OS ({@code .venv/bin/python} or {@code .venv\Scripts\python.exe})</li>
     *   <li>System fallback binary ({@code python3} on Unix/macOS or {@code python} on Windows)</li>
     * </ol>
     *
     * @param configPythonPath Custom Python path from config file (can be null or blank)
     * @return The absolute or command path to the Python executable
     */
    public static String getPythonExecutablePath(String configPythonPath) 
    {
        if (configPythonPath != null && !configPythonPath.isBlank()) 
        {
            LOG.debug("Using configured Python path: {}", configPythonPath);
            return configPythonPath;
        }

        String sysPropertyPath = System.getProperty("bot.pythonpath");
        if (sysPropertyPath != null && !sysPropertyPath.isBlank()) 
        {
            LOG.debug("Using system property Python path: {}", sysPropertyPath);
            return sysPropertyPath;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        String venvRelPath = isWindows ? ".venv\\Scripts\\python.exe" : ".venv/bin/python";
        File venvFile = new File(System.getProperty("user.dir"), venvRelPath);

        if (venvFile.exists() && venvFile.isFile()) 
        {
            LOG.debug("Found virtual environment Python binary at: {}", venvFile.getAbsolutePath());
            return venvFile.getAbsolutePath();
        }

        String systemFallback = isWindows ? "python" : "python3";
        LOG.warn(".venv Python binary not found at [{}]. Falling back to system executable: '{}'", 
                 venvFile.getAbsolutePath(), systemFallback);

        return systemFallback;
    }
    
    /**
     * Overload for calls without explicit configuration parameters.
     */
    public static String getPythonExecutablePath() 
    {
        return getPythonExecutablePath(null);
    }
}