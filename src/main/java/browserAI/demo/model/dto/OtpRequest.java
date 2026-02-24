package browserAI.demo.model.dto;

import jakarta.validation.constraints.NotBlank;

public class OtpRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "otp is required")
    private String otp;

    public OtpRequest() {}

    public OtpRequest(String userId, String otp) {
        this.userId = userId;
        this.otp = otp;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }
}
