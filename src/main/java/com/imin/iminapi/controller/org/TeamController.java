package com.imin.iminapi.controller.org;

import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.org.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/org/team")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) { this.teamService = teamService; }

    @GetMapping
    public List<TeamMemberDto> list(@CurrentUser AuthPrincipal p) { return teamService.list(p); }

    @PostMapping("/invite")
    public InviteResponse invite(@CurrentUser AuthPrincipal p, @Valid @RequestBody InviteRequest body) {
        return teamService.invite(p, body);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@CurrentUser AuthPrincipal p, @PathVariable UUID userId) {
        teamService.remove(p, userId);
    }
}
