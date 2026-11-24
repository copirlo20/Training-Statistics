package vn.trainingstats.catalog;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.trainingstats.common.ApiException;

@RestController
@RequestMapping("/api/v1/catalogs")
public class CatalogController {

    public record StatusRequest(Boolean active) {
    }

    private final CatalogService catalogs;

    public CatalogController(CatalogService catalogs) {
        this.catalogs = catalogs;
    }

    /** Mọi danh mục, cho mọi người đã đăng nhập (để hiển thị tên cấp bậc, chức vụ...). */
    @GetMapping
    public Map<String, List<CatalogService.Item>> all() {
        return catalogs.all();
    }

    @PostMapping("/{type}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CatalogService.Item create(@PathVariable String type, @RequestBody CatalogService.SaveRequest body) {
        return catalogs.create(CatalogService.Type.fromPath(type), body);
    }

    @PutMapping("/{type}/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CatalogService.Item update(@PathVariable String type, @PathVariable long id,
                                      @RequestBody CatalogService.SaveRequest body) {
        return catalogs.update(CatalogService.Type.fromPath(type), id, body);
    }

    @PatchMapping("/{type}/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CatalogService.Item status(@PathVariable String type, @PathVariable long id, @RequestBody StatusRequest body) {
        if (body.active() == null) {
            throw ApiException.invalidField("active", "Thiếu trạng thái.");
        }
        return catalogs.setActive(CatalogService.Type.fromPath(type), id, body.active());
    }
}
