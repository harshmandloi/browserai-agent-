package browserAI.demo.exception;

public class DownloadTimeoutException extends RuntimeException {
    public DownloadTimeoutException(String message) {
        super(message);
    }
}
