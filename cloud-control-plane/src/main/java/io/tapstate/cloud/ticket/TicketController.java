package io.tapstate.cloud.ticket;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.common.api.BaseController;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/api/tickets")
@RequiredArgsConstructor
@Tag(name = "Support tickets", description = "Cloud session-owned Jira Service Management requests")
public class TicketController extends BaseController {

    private final JiraTicketService ticketService;

    @GetMapping
    @Operation(summary = "List the current user's support tickets")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ticket page"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<ApiResponse<JiraTicketService.TicketPage>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(ticketService.list(page, size));
    }

    @PostMapping
    @Operation(summary = "Create a support request for the current Cloud user")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Ticket created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request fields rejected"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Cloud account email is not verified"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cloud email could not be uniquely resolved to a Jira customer"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Jira Service Management is unavailable"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Jira configuration is missing")
    })
    public ResponseEntity<ApiResponse<JiraTicketService.Ticket>> create(@RequestBody CreateTicketRequest request) {
        if (request == null) throw new BusinessException(AppErrorCode.TICKET_REQUEST_REJECTED, 400);
        return okCreated(ticketService.create(request.summary(), request.description()));
    }

    @GetMapping("/{cloudTicketId}")
    @Operation(summary = "Read one of the current user's support tickets")
    public ResponseEntity<ApiResponse<JiraTicketService.Ticket>> get(@PathVariable String cloudTicketId) {
        return ok(ticketService.get(cloudTicketId));
    }

    @PostMapping("/{cloudTicketId}/close")
    @Operation(summary = "Close one of the current user's support tickets when Jira permits it")
    public ResponseEntity<ApiResponse<JiraTicketService.Ticket>> close(@PathVariable String cloudTicketId) {
        return ok(ticketService.close(cloudTicketId));
    }

    public record CreateTicketRequest(
        @NotBlank @Size(max = 255) String summary,
        @NotBlank @Size(max = 10000) String description
    ) {}
}
