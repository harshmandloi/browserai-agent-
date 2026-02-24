package browserAI.demo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Request DTO for scheduling auto web check-in.
 * Works for ANY airline — agent dynamically discovers the check-in page.
 */
public class CheckInRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "airline is required (e.g., indigo, airindia, spicejet, vistara)")
    private String airline;

    @NotBlank(message = "PNR / booking reference is required")
    private String pnr;

    @NotBlank(message = "lastName is required for check-in")
    private String lastName;

    private String email;

    @NotNull(message = "departureDateTime is required (ISO format: 2026-02-20T14:00:00)")
    private LocalDateTime departureDateTime;

    private Integer hoursBeforeDeparture = 24;

    public CheckInRequest() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAirline() { return airline; }
    public void setAirline(String airline) { this.airline = airline; }

    public String getPnr() { return pnr; }
    public void setPnr(String pnr) { this.pnr = pnr; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public LocalDateTime getDepartureDateTime() { return departureDateTime; }
    public void setDepartureDateTime(LocalDateTime departureDateTime) { this.departureDateTime = departureDateTime; }

    public Integer getHoursBeforeDeparture() { return hoursBeforeDeparture; }
    public void setHoursBeforeDeparture(Integer hoursBeforeDeparture) { this.hoursBeforeDeparture = hoursBeforeDeparture; }
}
