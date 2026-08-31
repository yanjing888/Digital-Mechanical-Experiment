package cn.ncut.lab.web;

public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(String message) {
        this(400, message);
    }

    public int getStatus() {
        return status;
    }
}
