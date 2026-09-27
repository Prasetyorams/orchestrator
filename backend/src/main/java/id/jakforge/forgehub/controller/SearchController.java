package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Pencarian menyeluruh di bilah atas. */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public List<Map<String, Object>> search(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                            @RequestParam(name = "q", required = false) String query) {
        return searchService.search(principal, query);
    }
}
