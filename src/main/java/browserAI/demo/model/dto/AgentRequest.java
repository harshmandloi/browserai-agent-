package browserAI.demo.model.dto;

import jakarta.validation.constraints.NotBlank;

public class AgentRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "input is required")
    private String input;

    public AgentRequest() {}

    public AgentRequest(String userId, String input) {
        this.userId = userId;
        this.input = input;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
}
