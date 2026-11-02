package com.example.kb.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.kb.graph.Edge;
import com.example.kb.graph.Graph;
import com.example.kb.graph.GraphBuilder;
import com.example.kb.graph.Node;
import com.example.kb.graph.NodeRef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NginxExtractorTest {

    private static Graph graph;

    @BeforeAll
    static void extract() throws Exception {
        GraphBuilder builder = new GraphBuilder();
        new NginxExtractor().extract(Fixtures.dir("docker"), builder);
        graph = builder.build();
    }

    private static NodeRef route(String id) {
        return new NodeRef("Route", id);
    }

    private static NodeRef service(String id) {
        return new NodeRef("Service", id);
    }

    @Test
    @DisplayName("Každý location patrí nginx")
    void should_linkRoutesToNginx_whenConfParsed() {
        assertThat(graph.count("Route")).isEqualTo(5);
        assertThat(graph.edges()).filteredOn(e -> e.type().equals("ROUTES"))
                .hasSize(5)
                .allMatch(e -> e.from().equals(service("nginx")));
    }

    @Test
    @DisplayName("proxy_pass na host vytvorí PROXIES_TO")
    void should_proxyToService_whenProxyPassHasHost() {
        assertThat(graph.edges()).contains(
                new Edge("PROXIES_TO", route("/crm/"), service("crm-web")),
                new Edge("PROXIES_TO", route("/*"), service("liferay")));
    }

    @Test
    @DisplayName("proxy_pass cez premennú sa vyrieši zo set")
    void should_resolveVariable_whenProxyPassUsesSet() {
        assertThat(graph.edges()).contains(new Edge("PROXIES_TO", route("/sso/"), service("sso-mock")));
    }

    @Test
    @DisplayName("Presmerovanie ide na location s najdlhším prefixom")
    void should_redirectToLongestPrefix_whenReturn302() {
        assertThat(graph.edges()).contains(
                new Edge("REDIRECTS_TO", route("/web/crm/blacklist"), route("/crm/")),
                new Edge("REDIRECTS_TO", route("/"), route("/web/crm/blacklist")));
    }

    @Test
    @DisplayName("Presná zhoda a druh trasy sú vo vlastnostiach")
    void should_markExactAndKind_whenRouteCreated() {
        Node root = node(route("/"));
        Node crm = node(route("/crm/"));

        assertThat(root.props()).containsEntry("exact", true).containsEntry("kind", "redirect");
        assertThat(crm.props()).containsEntry("kind", "proxy").doesNotContainKey("exact");
    }

    private static Node node(NodeRef ref) {
        return graph.nodes().stream().filter(n -> n.ref().equals(ref)).findFirst().orElseThrow();
    }
}
