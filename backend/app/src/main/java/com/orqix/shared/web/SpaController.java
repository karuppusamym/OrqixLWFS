package com.orqix.shared.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client-side routes of the SPA (served same-origin from {@code static/}) are answered with {@code index.html}; the
 * React router takes over. API, OAuth2, login/logout and actuator paths are never forwarded.
 */
@Controller
public class SpaController {

    @GetMapping({"/w/**", "/workspaces/**", "/settings/**", "/admin/**", "/tasks/**", "/requests/**", "/runs/**",
            "/workflows/**", "/notifications/**", "/start/**", "/not-found", "/forbidden"})
    String forward() {
        return "forward:/index.html";
    }
}
