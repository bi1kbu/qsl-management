package com.bi1kbu.qslmanagement.menu;

import java.util.List;

public record MenuModuleDefinition(
    String key,
    String title,
    String viewPermission,
    String editPermission,
    List<String> viewDependencies,
    List<String> editDependencies
) {
}
