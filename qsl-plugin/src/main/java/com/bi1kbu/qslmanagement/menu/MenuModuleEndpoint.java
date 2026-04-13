package com.bi1kbu.qslmanagement.menu;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/apis/qsl-management.halo.run/v1alpha1/menu-modules")
public class MenuModuleEndpoint {

    private final MenuModuleRegistry menuModuleRegistry;

    public MenuModuleEndpoint(MenuModuleRegistry menuModuleRegistry) {
        this.menuModuleRegistry = menuModuleRegistry;
    }

    @GetMapping
    public List<MenuModuleDefinition> listModules() {
        return menuModuleRegistry.list();
    }

    @GetMapping("/{key}")
    public ResponseEntity<MenuModuleDefinition> getModule(@PathVariable String key) {
        return menuModuleRegistry
            .findByKey(key)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
