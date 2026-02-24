package browserAI.demo.model.dto;

import jakarta.validation.constraints.NotBlank;

public class CredentialRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "portal is required")
    private String portal;

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "password is required")
    private String password;

    public CredentialRequest() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPortal() { return portal; }
    public void setPortal(String portal) { this.portal = portal; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
