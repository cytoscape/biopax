package org.cytoscape.biopax.internal;

import org.cytoscape.biopax.internal.util.BioPaxReaderError;
import org.cytoscape.model.*;

import java.util.*;

/**
 * Created by rodche on 2015-10-19.
 */
public class CustomSifParser {
    private final CyNetwork network;
    private Map<Object, CyNode> nMap;

    public CustomSifParser(final CyNetwork network, final CyServices serviceRegistrar)
    {
        this.nMap = new HashMap<Object,CyNode>();
        this.network = network;
    }

    public void parse(final String row) {
        String[] parts = row.split("\\t", -1); //allow empty tokens

        if(parts==null || parts.length<6)
            throw new BioPaxReaderError("Bad SIF entry: " + row);

        final CyNode source = createNode(parts[0]);
        final String interactionType = parts[1];
        final CyNode target = createNode(parts[2]);

        final CyEdge edge = network.addEdge(source, target, true);
        network.getRow(edge).set(CyEdge.INTERACTION, interactionType);
        String edgeName = network.getRow(source).get(CyNetwork.NAME, String.class) +
                " ("+interactionType+") " + network.getRow(target).get(CyNetwork.NAME, String.class);
        network.getRow(edge).set(CyNetwork.NAME, edgeName);

        // add edge attributes
        if (edge != null) {
            addEdgeAttributes(edge, "datasource", parts[3]);
            addEdgeAttributes(edge, "publication", parts[4]);
            addEdgeAttributes(edge, "pathway", parts[5]);
        }
    }

    private CyNode createNode(final String uri) {
        CyNode node = nMap.get(uri);
        if (node == null) {
            // Node does not exist yet, create it
            node = network.addNode();
            network.getRow(node).set("name", uri);
            nMap.put(uri, network.getNode(node.getSUID()));
        }
        return node;
    }

    private void addEdgeAttributes(final CyEdge element, final String column, final String entry)
    {
        final CyTable table = network.getRow(element).getTable();
        if (table.getColumn(column) == null)
            table.createListColumn(column, String.class, false);
        List<String> value = Arrays.asList(entry.split(";"));
        network.getRow(element).set(column, value);
    }

}
