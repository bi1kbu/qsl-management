package com.bi1kbu.qslmanagement.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class QslRoleTemplateContractTest {

    private static final Path ROLE_TEMPLATE_PATH = Path.of(
        "src/main/resources/extensions/qsl-menu-role-templates.yaml"
    );

    @Test
    void shouldCoverConsoleCustomEndpointRules() throws IOException {
        var rules = loadRules();

        // QslOverviewConsoleEndpoint
        assertHasRule(rules, "console.api.qsl-management.halo.run", "overview", "get");

        // QslConsoleApiEndpoint
        assertHasRule(rules, "console.api.qsl-management.halo.run", "reports", "get");
        assertHasRule(rules, "console.api.qsl-management.halo.run", "mail-send-confirms", "create");
        assertHasRule(rules, "console.api.qsl-management.halo.run", "mail-receive-confirms", "create");
        assertHasRule(rules, "console.api.qsl-management.halo.run", "exchange-requests", "create");

        assertHasRule(rules, "console.api.qsl-management.halo.run", "imports", "create");
        assertHasRule(rules, "console.api.qsl-management.halo.run", "imports", "get");
        assertHasRule(rules, "console.api.qsl-management.halo.run", "exports", "create");
        assertHasRule(rules, "console.api.qsl-management.halo.run", "exports", "get");
    }

    @Test
    void shouldCoverPublicCustomEndpointRules() throws IOException {
        var rules = loadRules();

        // QslPublicApiEndpoint + QslOverviewPublicEndpoint
        assertHasRule(rules, "api.qsl-management.halo.run", "qso-public", "get");
        assertHasRule(rules, "api.qsl-management.halo.run", "exchange-public", "create");
        assertHasRule(rules, "api.qsl-management.halo.run", "receipt-public", "create");
        assertHasRule(rules, "api.qsl-management.halo.run", "receipt-public", "get");
        assertHasRule(rules, "api.qsl-management.halo.run", "overview-public", "get");
        assertHasRule(rules, "api.qsl-management.halo.run", "cards", "get");
    }

    @Test
    void shouldAggregatePublicRolesToAnonymous() throws IOException {
        var docs = loadRoleDocs();

        assertHasAnonymousAggregateRole(docs, "qsl-management-public-view");
        assertHasAnonymousAggregateRole(docs, "qsl-management-public-submit");
    }

    @Test
    void shouldNotUsePluginPrefixInResources() throws IOException {
        var rules = loadRules();
        var hasPrefixedResource = rules.stream()
            .flatMap(rule -> rule.resources().stream())
            .anyMatch(resource -> resource.startsWith("qsl-management/"));

        assertTrue(!hasPrefixedResource, "resources 不应包含 qsl-management/ 前缀");
    }

    private void assertHasAnonymousAggregateRole(List<Map<String, Object>> docs, String roleName) {
        var matched = docs.stream().anyMatch(doc -> {
            var metadata = toMap(doc.get("metadata"));
            if (!roleName.equals(stringValue(metadata.get("name")))) {
                return false;
            }
            var labels = toMap(metadata.get("labels"));
            return "true".equals(stringValue(labels.get("rbac.authorization.halo.run/aggregate-to-anonymous")));
        });

        assertTrue(matched, "缺少 anonymous 聚合角色：" + roleName);
    }

    private void assertHasRule(List<RoleRule> rules, String apiGroup, String resource, String verb) {
        var matched = rules.stream().anyMatch(rule ->
            rule.apiGroups().contains(apiGroup)
                && rule.resources().contains(resource)
                && rule.verbs().contains(verb)
        );
        assertTrue(matched, "权限模板缺少规则：apiGroup=" + apiGroup + ", resource=" + resource + ", verb=" + verb);
    }

    private List<RoleRule> loadRules() throws IOException {
        var docs = loadRoleDocs();
        var rules = new ArrayList<RoleRule>();
        for (var doc : docs) {
            var roleRules = toList(doc.get("rules"));
            for (var roleRuleObj : roleRules) {
                var roleRule = toMap(roleRuleObj);
                rules.add(new RoleRule(
                    toStringList(roleRule.get("apiGroups")),
                    toStringList(roleRule.get("resources")),
                    toStringList(roleRule.get("verbs"))
                ));
            }
        }
        return rules;
    }

    private List<Map<String, Object>> loadRoleDocs() throws IOException {
        var yamlContent = Files.readString(ROLE_TEMPLATE_PATH);
        var yaml = new Yaml();
        var docs = new ArrayList<Map<String, Object>>();
        for (var document : yaml.loadAll(yamlContent)) {
            if (document instanceof Map<?, ?> map) {
                var normalized = new LinkedHashMap<String, Object>();
                map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                docs.add(normalized);
            }
        }
        return docs;
    }

    private Map<String, Object> toMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var normalized = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        return normalized;
    }

    private List<Object> toList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record RoleRule(
        List<String> apiGroups,
        List<String> resources,
        List<String> verbs
    ) {
    }
}
