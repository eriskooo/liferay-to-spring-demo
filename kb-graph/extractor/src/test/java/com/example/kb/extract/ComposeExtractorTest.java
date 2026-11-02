package com.example.kb.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.kb.graph.Edge;
import com.example.kb.graph.Graph;
import com.example.kb.graph.GraphBuilder;
import com.example.kb.graph.Node;
import com.example.kb.graph.NodeRef;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComposeExtractorTest {

    private static NodeRef service(String id) {
        return new NodeRef("Service", id);
    }

    private static Graph extract(GraphBuilder builder) throws Exception {
        new ComposeExtractor().extract(Fixtures.dir("docker"), builder);
        return builder.build();
    }

    private static GraphBuilder withModules(String... ids) {
        GraphBuilder builder = new GraphBuilder();
        for (String id : ids) {
            builder.node("Module", id, Map.of());
        }
        return builder;
    }

    private static Node node(Graph g, NodeRef ref) {
        return g.nodes().stream().filter(n -> n.ref().equals(ref)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Načíta služby s image a profilom")
    void should_createServices_whenComposeParsed() throws Exception {
        Graph g = extract(new GraphBuilder());

        assertThat(g.count("Service")).isEqualTo(6);
        assertThat(node(g, service("redis")).props()).containsEntry("image", "redis:7.4-alpine")
                .containsEntry("ports", "6379:6379");
        assertThat(node(g, service("sso-mock")).props()).containsEntry("profile", "sso");
        assertThat(node(g, service("crm-web")).props()).containsEntry("build", "../crm-web");
    }

    @Test
    @DisplayName("depends_on ako mapa aj zoznam vytvorí hrany")
    void should_createDependsOn_whenListOrMap() throws Exception {
        Graph g = extract(new GraphBuilder());

        assertThat(g.edges()).contains(
                new Edge("DEPENDS_ON", service("crm-web"), service("postgres")),
                new Edge("DEPENDS_ON", service("nginx"), service("crm-web")));
    }

    @Test
    @DisplayName("Služba buildovaná z adresára s pom.xml je BUILT_FROM modul")
    void should_linkModule_whenBuildDirHasPom() throws Exception {
        Graph g = extract(withModules("crm-web"));

        assertThat(g.edges()).contains(new Edge("BUILT_FROM", service("crm-web"), new NodeRef("Module", "crm-web")));
    }

    @Test
    @DisplayName("Liferay je BUILT_FROM legacy WAR moduly podľa explicitnej mapy")
    void should_linkLegacyWars_whenServiceIsLiferay() throws Exception {
        Graph g = extract(withModules("customercare-portlets", "customercare-theme"));

        assertThat(g.edges()).contains(
                new Edge("BUILT_FROM", service("liferay"), new NodeRef("Module", "customercare-portlets")),
                new Edge("BUILT_FROM", service("liferay"), new NodeRef("Module", "customercare-theme")));
    }

    @Test
    @DisplayName("Bez existujúceho modulu sa BUILT_FROM nevytvorí")
    void should_skipBuiltFrom_whenModuleMissing() throws Exception {
        Graph g = extract(new GraphBuilder());

        assertThat(g.edges()).noneMatch(e -> e.type().equals("BUILT_FROM"));
    }
}
