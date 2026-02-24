package browserAI.demo.model.dto;

import jakarta.validation.constraints.NotBlank;

public class CaptchaRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "answer is required")
    private String answer;

    public CaptchaRequest() {}

    public CaptchaRequest(String userId, String answer) {
        this.userId = userId;
        this.answer = answer;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
}
