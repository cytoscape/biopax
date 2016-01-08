package org.cytoscape.biopax.internal;

/*
 * #%L
 * Cytoscape BioPAX Core App.
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2006 - 2013 The Cytoscape Consortium
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as 
 * published by the Free Software Foundation, either version 2.1 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.biopax.paxtools.controller.AbstractTraverser;
import org.biopax.paxtools.controller.ModelUtils;
import org.biopax.paxtools.controller.ObjectPropertyEditor;
import org.biopax.paxtools.controller.PropertyEditor;
import org.biopax.paxtools.controller.SimpleEditorMap;
import org.biopax.paxtools.converter.LevelUpgrader;
import org.biopax.paxtools.io.SimpleIOHandler;
import org.biopax.paxtools.io.sbgn.L3ToSBGNPDConverter;
import org.biopax.paxtools.model.BioPAXElement;
import org.biopax.paxtools.model.BioPAXLevel;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level3.*;
import org.biopax.paxtools.model.level3.Process;
import org.biopax.paxtools.pattern.miner.*;
import org.biopax.paxtools.util.ClassFilterSet;
import org.biopax.paxtools.util.Filter;
import org.cytoscape.biopax.internal.util.AttributeUtil;
import org.cytoscape.biopax.internal.util.ClassLoaderHack;
import org.cytoscape.biopax.internal.util.ExternalLink;
import org.cytoscape.biopax.internal.util.ExternalLinkUtil;
import org.cytoscape.model.CyEdge;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNetworkFactory;
import org.cytoscape.model.CyNode;
import org.cytoscape.model.CyRow;
import org.cytoscape.model.subnetwork.CyRootNetwork;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Maps a BioPAX Model to Cytoscape network.
 *
 * @author Ethan Cerami, Igor Rodchenkov (major re-factoring using PaxTools API)
 */
public class BioPaxMapper {
	
	public static final Logger log = LoggerFactory.getLogger(BioPaxMapper.class);
	
	/**
	 * Cytoscape Attribute:  BioPAX object's URI.
	 */
	public static final String BIOPAX_URI = "URI";

	/**
	 * Network Attribute: NETWORK/MAPPING TYPE
	 */
	public static final String BIOPAX_NETWORK = "BIOPAX_NETWORK";
	
	/**
	 * BioPax Node Attribute: Entity TYPE
	 */
	public static final String BIOPAX_ENTITY_TYPE = "BIOPAX_TYPE";

	/**
	 * BioPax Node Attribute: CHEMICAL_MODIFICATIONS_LIST
	 */
	public static final String BIOPAX_CHEMICAL_MODIFICATIONS_LIST = "CHEMICAL_MODIFICATIONS";

	/**
	 * Hidden Node Attribute: unification (id) web links
	 */
	public static final String BIOPAX_UNIFICATION_REFERENCES = "UNIFICATION_REFERENCES";

	/**
	 * Hidden Node Attribute: relationship web links
	 */
	public static final String BIOPAX_RELATIONSHIP_REFERENCES = "RELATIONSHIP_REFERENCES";

	/**
	 * Hidden Node Attribute: web links to publications
	 */
	public static final String BIOPAX_PUBLICATION_REFERENCES = "PUBLICATION_REFERENCES";


	public static final String BIOPAX_UNIFICATION = "UNIFICATION";
	public static final String BIOPAX_RELATIONSHIP = "RELATIONSHIP";
	public static final String BIOPAX_PUBLICATION = "PUBLICATION";
	public static final String BIOPAX_IHOP_LINKS = "IHOP_LINKS";
	public static final String PHOSPHORYLATION_SITE = "phosphorylation site";
	public static final String PROTEIN_PHOSPHORYLATED = "Protein-phosphorylated";

	private final Model model;
	private final CyNetworkFactory networkFactory;
	
	// BioPAX ID (URI) to CyNode map
	// remark: nodes's CyTable will also have 'URI' (RDF Id) column
	private final Map<BioPAXElement, CyNode> 
		bpeToCyNodeMap = new HashMap<BioPAXElement, CyNode>();
	

	/**
	 * Constructor. 
	 * Use this one if you do not plan to create new networks.
	 * 
	 * @param model BioPAX Model
	 * @param cyNetworkFactory Cytoscape network factory
	 */
	public BioPaxMapper(Model model, CyNetworkFactory cyNetworkFactory) {
		this.model = model;
		this.networkFactory = cyNetworkFactory;
	}
	
	public CyNetwork createCyNetwork(String networkName, CyRootNetwork rootNetwork)  {
		CyNetwork network = (rootNetwork == null) 
				? networkFactory.createNetwork() 
					: rootNetwork.addSubNetwork();
	
		// First, create nodes for all Entity class objects
		createEntityNodes(network);

		// create edges
		createInteractionEdges(network);
		createComplexEdges(network);
		
		// TODO create pathwayComponent edges (requires pathway nodes)?
		
		// create PE->memberPE edges!
		createMemberEdges(network);
		
		// Finally, set network attributes:
		
		// name
		AttributeUtil.set(network, network, CyNetwork.NAME, networkName, String.class);
		
		// default Quick Find Index
		AttributeUtil.set(network, network, "quickfind.default_index", CyNetwork.NAME, String.class);
		
		return network;
	}
	
	private void createMemberEdges(CyNetwork network) {
		// for each PE,
		for (PhysicalEntity par : model.getObjects(PhysicalEntity.class)) {
			Set<PhysicalEntity> members = par.getMemberPhysicalEntity();
			if(members.isEmpty()) 
				continue;
					
			CyNode cyParentNode = bpeToCyNodeMap.get(par);
			assert cyParentNode != null : "cyParentNode is NULL.";
			// for each its member PE, add the directed edge
			for (PhysicalEntity member : members) 
			{
				CyNode cyMemberNode = bpeToCyNodeMap.get(member);
				CyEdge edge = network.addEdge(cyParentNode, cyMemberNode, true);
				AttributeUtil.set(network, edge, "interaction", "member", String.class);
			}
		}
	}


	private void createEntityNodes(CyNetwork network) {
		Set<Entity> entities = model.getObjects(Entity.class);
		for(Entity bpe: entities) {	
			// do not make nodes for top/main pathways
			if(bpe instanceof Pathway) {
				if(bpe.getParticipantOf().isEmpty()
					&& ((Process)bpe).getPathwayComponentOf().isEmpty())
					continue;
			}
			
			//  Create node symbolizing the interaction
			CyNode node = network.addNode();
			bpeToCyNodeMap.put(bpe, node);
				           
			// traverse
			createAttributesFromProperties(bpe, model, node, network);
		}
		
		if(log.isDebugEnabled())
			log.debug(network.getRow(network).get(CyNetwork.NAME, String.class) 
				+ "" + network.getNodeList().size() + " nodes created.");
	}


	private void createInteractionEdges(CyNetwork network) {
		//  Extract the List of all Interactions
		Collection<Interaction> interactionList = model.getObjects(Interaction.class);

		for (Interaction itr : interactionList) {	
			if(log.isTraceEnabled()) {
				log.trace("Mapping " + itr.getModelInterface().getSimpleName() 
					+ " edges : " + itr.getUri());
			}
			
			if (itr instanceof Conversion) {
				addConversionInteraction(network, (Conversion)itr);
			} else if (itr instanceof Control) {
				addControlInteraction(network, (Control) itr);
			} else {
				addPhysicalInteraction(network, itr);
			}
		}
	}


	private void createComplexEdges(CyNetwork network) {
		// iterate through all pe's
		for (Complex complexElement : model.getObjects(Complex.class)) {
			Set<PhysicalEntity> members = complexElement.getComponent();
			if(members.isEmpty()) 
				continue;

			// get node
			CyNode complexCyNode = bpeToCyNodeMap.get(complexElement);
			
			// get all components. There can be 0 or more
			for (PhysicalEntity member : members) 
			{
				CyNode complexMemberCyNode = bpeToCyNodeMap.get(member);
				// create edge, set attributes
				CyEdge edge = network.addEdge(complexCyNode, complexMemberCyNode, true);
				AttributeUtil.set(network, edge, "interaction", "contains", String.class);	
			}
		}
	}

	/*
	 * Adds a Physical Interaction, such as a binding interaction between
	 * two proteins.
	 */
	private void addPhysicalInteraction(CyNetwork network, Interaction interactionElement) {
		//  Add all Participants
		Collection<Entity> participantElements = interactionElement.getParticipant();
		for (Entity participantElement : participantElements) {
			linkNodes(network, interactionElement, participantElement, "participant");
		}
	}

	/*
	 * Adds a Conversion Interaction.
	 */
	private void addConversionInteraction(CyNetwork network, Conversion interactionElement) {
		//  Add Left Side of Reaction
		Collection<PhysicalEntity> leftSideElements = interactionElement.getLeft();
		for (PhysicalEntity leftElement: leftSideElements) {
			linkNodes(network, interactionElement, leftElement, "left");
		}

		//  Add Right Side of Reaction
		Collection<PhysicalEntity> rightSideElements = interactionElement.getRight();
		for (PhysicalEntity rightElement : rightSideElements) {
			linkNodes(network, interactionElement, rightElement, "right");
		}
	}

	/*
	 * Add Edges Between Interaction/Complex Node and Physical Entity Node.
	 */
	private void linkNodes(CyNetwork network, BioPAXElement bpeA, BioPAXElement bpeB, String type) 
	{	
		// Note: getCyNode also assigns cellular location attribute...
		CyNode nodeA = bpeToCyNodeMap.get(bpeA);
		if(nodeA == null) {
			log.debug("linkNodes: no node was created for " 
				+ bpeA.getModelInterface() + " " + bpeA.getUri());
			return; //e.g., we do not create any pathway nodes currently...
		}
		
		CyNode nodeB = bpeToCyNodeMap.get(bpeB);
		if(nodeB == null) {
			log.debug("linkNodes: no node was created for " 
					+ bpeB.getModelInterface() + " " + bpeB.getUri());
			return; //e.g., we do not create any pathway nodes currently...
		}
		
		CyEdge edge = null;
		String a = getName(bpeA);
		String b = getName(bpeB);	
		if (type.equals("right") || type.equals("cofactor")
				|| type.equals("participant")) {
			edge = network.addEdge(nodeA, nodeB, true);
			AttributeUtil.set(network, edge, CyNetwork.NAME, a + type + b, String.class);
		} else {
			edge = network.addEdge(nodeB, nodeA, true);
			AttributeUtil.set(network, edge, CyNetwork.NAME, b + type + a, String.class);
		}

		AttributeUtil.set(network, edge, "interaction", type, String.class);
		
	}

	
	/*
	 * Adds a BioPAX Control Interaction.
	 */
	private void addControlInteraction(CyNetwork network, Control control) {
		Collection<Process> controlledList = control.getControlled();		
		for (Process process : controlledList) {
			// Determine the BioPAX Edge Type
			String typeStr = "controlled"; //default
			ControlType cType = control.getControlType();
			typeStr = (cType == null) ? typeStr : cType.toString();
			//edge direction (trick) - from control to process (like for 'right', 'cofactor', 'participant')
			linkNodes(network, process, control, typeStr); 
		} 

		Collection<Controller> controllerList = control.getController();
		for (Controller controller : controllerList) {
			// directed edge - from Controller to Control (like 'left')
			linkNodes(network, control, controller, "controller");
		}

		// cofactor relationships
		if(control instanceof Catalysis) {
			Collection<PhysicalEntity> coFactorList = ((Catalysis) control).getCofactor();
			for(PhysicalEntity cofactor : coFactorList) {
				// direction - from control to cofactor (like 'right', 'participant', 'controlled')
				linkNodes(network, control, cofactor, "cofactor");
			}
		}	
	}


	/*
	 * Given a binding element (complex or interaction)
	 * and type (like left or right),
	 * returns chemical modification (abbreviated form).
	 */
	private static NodeAttributesWrapper getInteractionChemicalModifications(BioPAXElement participantElement) 
	{
		if(participantElement == null) {
			return null;
		}

		final Set<String> chemicalModificationsSet = new HashSet<String>();

		// if we are dealing with participant processes (interactions
		// or complexes), we have to go through the participants to get the
		// proper chemical modifications
		Collection<?> modificationFeatures = getValues(participantElement, "feature");
		if (modificationFeatures != null) {
			for (Object modification : modificationFeatures) {
				if (modification != null) {
					Object value = getValue((BioPAXElement) modification, "modificationType");
					if (value != null) {
						String mod = value.toString();
						//remove the ClassName_ prefix and square braces -
						mod = mod.substring(mod.indexOf("_") + 1).replaceAll("\\[|\\]", "");
						chemicalModificationsSet.add(mod);
					}
				}
			}
		}

		Collection<?> modificationNotFeatures = getValues(participantElement, "notFeature");
		if (modificationNotFeatures != null) {
			for (Object modification : modificationNotFeatures) {
				if (modification != null) {
					Object value = getValue((BioPAXElement) modification, "modificationType");
					if (value != null) {
						String mod = value.toString();
						//remove the ClassName_ prefix and square braces;
						//add "!" upfront (i.e. "NOT")
						mod = "!" + mod.substring(mod.indexOf("_") + 1).replaceAll("\\[|\\]", "");
						chemicalModificationsSet.add(mod);
					}
				}
			}
		}

		return new NodeAttributesWrapper(chemicalModificationsSet);
	}

	
    private static void createExtraXrefAttributes(BioPAXElement resource, CyNetwork network, CyNode node) {
		
		// try getting the primary UniProt ID from the URI
    	// to create UNIPROT attribute
    	// (does not work well with generic PRs though)
    	if(resource instanceof PhysicalEntity || 
    			resource instanceof EntityReference) 
    	{
    		String u = resource.getUri();
    		if(resource instanceof SimplePhysicalEntity && 
    			((SimplePhysicalEntity) resource).getEntityReference() != null)
    			u = ((SimplePhysicalEntity) resource).getEntityReference().getUri();
			
    		if(u.startsWith("http://identifiers.org/uniprot")) { 
				// /uniprot.isoform/ works here as well
				String id = u.substring(u.lastIndexOf('/')+1);
				AttributeUtil.set(network, node, "UNIPROT", id, String.class);
			}
    	}
     	
    	//add special simple (String) uniprot, ncbi gene, gene symbol attributes
    	// (do not create those for generic ER/PE, - impossible to define a "primary" ID)
		for (Xref link : getXRefs(resource, Xref.class, false)) {
			if(link.getDb() == null || link.getDb().isEmpty()
					|| link.getId() == null || link.getId().isEmpty())
				continue; // too bad (data issue...); skip it			
			// try to detect and add several important ID attributes first
			// (it works better, if at all, when the biopax model was normalized)
			//chances are, if data were normalized, we get some more primary accession IDs:
			createSpecialXrefAttribute(resource, network, node, link);
		}
    	
    	// ihop links
		String stringRef = ihopLinks(resource);
		if (stringRef != null) {
			AttributeUtil.set(network, node, CyNetwork.HIDDEN_ATTRS, BIOPAX_IHOP_LINKS, stringRef, String.class);
		}

		//these collections, one per xref class, are to store standard IDs only (no db name)
		List<String> uniXrefList = new ArrayList<String>();
		List<String> relXrefList = new ArrayList<String>();
		List<String> pubXrefList = new ArrayList<String>();
		//next are for (hidden) list attributes that contain more info about the xref
		List<String> uniLinkList = new ArrayList<String>();
		List<String> relLinkList = new ArrayList<String>();
		List<String> pubLinkList = new ArrayList<String>();
		
		// create several ID-list attributes from xrefs 
		// (including from members of/if it's a generic ER/PE)
		for (Xref link : getXRefs(resource, Xref.class, true)) {
			if(link.getDb() == null || link.getDb().isEmpty()
					|| link.getId() == null || link.getId().isEmpty())
				continue; // too bad (data issue...); skip it
			
			// then, for any xref, collect IDs
			StringBuffer temp = new StringBuffer();			
			temp.append(ExternalLinkUtil.createLink(link.getDb(), link.getId()));

			String str;
			
			if(link instanceof UnificationXref) {
				str = temp.toString();
				if(!uniLinkList.contains(str))
					uniLinkList.add(str);

				str = link.toString();
				if(!uniXrefList.contains(str))
					uniXrefList.add(str);
			}
			else if(link instanceof PublicationXref) {

				PublicationXref xl = (PublicationXref) link;
				temp.append(" ");
				if (!xl.getAuthor().isEmpty()) {
					temp.append(xl.getAuthor().toString() + " et al., ");
				}
				if (xl.getTitle() != null) {
					temp.append(xl.getTitle());
				}
				if (!xl.getSource().isEmpty()) {
					temp.append(" (" + xl.getSource().toString());
					if (xl.getYear() > 0) {
						temp.append(", " + xl.getYear());
					}
					temp.append(")");
				}

				str = temp.toString();
				if(!pubLinkList.contains(str))
					pubLinkList.add(str);

				str = link.toString();
				if(!pubXrefList.contains(str))
					pubXrefList.add(str);
			}
			else if(link instanceof RelationshipXref) {
				str = temp.toString();
				if(!relLinkList.contains(str))
					relLinkList.add(str);

				str = link.toString();
				if(!relXrefList.contains(str))
					relXrefList.add(str);
			}
		}
		
		AttributeUtil.set(network, node, BIOPAX_UNIFICATION, uniXrefList, String.class);
		AttributeUtil.set(network, node, BIOPAX_RELATIONSHIP, relXrefList, String.class);
		AttributeUtil.set(network, node, BIOPAX_PUBLICATION, pubXrefList, String.class);
		AttributeUtil.set(network, node, CyNetwork.HIDDEN_ATTRS, BIOPAX_UNIFICATION_REFERENCES, uniLinkList, String.class);
		AttributeUtil.set(network, node, CyNetwork.HIDDEN_ATTRS, BIOPAX_RELATIONSHIP_REFERENCES, relLinkList, String.class);
		AttributeUtil.set(network, node, CyNetwork.HIDDEN_ATTRS, BIOPAX_PUBLICATION_REFERENCES, pubLinkList, String.class);	
	}

    
    /*
	 * Create special individual String (not List) attributes from specific xref IDs, 
	 * HGNC Symbol and NCBI Gene; for others, though, including UniProt, unfortunately, 
	 * it's usually hard to find the "primary" ID among others, 
	 * without using external databases (it works better, if at all, 
	 * when the biopax model was normalized). But let's at least have one
	 * UniProt ID (by chance, if ther're many, unless it's already added).
     */
	private static void createSpecialXrefAttribute(BioPAXElement resource, CyNetwork network, CyNode node, Xref link) {
		final String db = link.getDb().toUpperCase().trim();
		final String id = link.getId().trim();
		if(db.equalsIgnoreCase("HGNC SYMBOL") //- official primary db name
				|| db.startsWith("HGNC") || db.startsWith("HUGO GENE")
				|| db.startsWith("GENE SYMBOL") || db.startsWith("GENE NAME")) {
			String exists = network.getRow(node).get("GENE SYMBOL", String.class);
			//won't replace any existing value (added first)
			if (exists == null && !id.startsWith("HGNC:")) //ignore HGNC:12345 IDs
				AttributeUtil.set(network, node, "GENE SYMBOL", id, String.class);
			
		} else if(db.equalsIgnoreCase("NCBI GENE") //main (official) db name
				|| db.equalsIgnoreCase("ENTREZ GENE") || db.equalsIgnoreCase("GENE ID")) {
			String exists = network.getRow(node).get("NCBI GENE", String.class);
			//won't replace any existing value (added first)
			if (exists == null)
				AttributeUtil.set(network, node, "NCBI GENE", id, String.class);
			
		} else if(db.startsWith("UNIPROT") 
				|| db.startsWith("SWISSPROT") || db.startsWith("SWISS-PROT")) {
			String exists = network.getRow(node).get("UNIPROT", String.class);
			//won't replace if found
			if (exists == null)
				AttributeUtil.set(network, node, "UNIPROT", id, String.class);
			
		}
	}


	public static void createAttributesFromProperties(final BioPAXElement element, final Model model,
			final CyNode node, final CyNetwork network) 
	{
		@SuppressWarnings("rawtypes")
		Filter<PropertyEditor> filter = new Filter<PropertyEditor>() {
			@Override
			// skips for entity-range properties (which map to edges rather than attributes!),
			// and several utility classes ranges (for which we do not want generate attributes or do another way)
			public boolean filter(PropertyEditor editor) {
				boolean pass = true;
				
				final String prop = editor.getProperty();
				if(editor instanceof ObjectPropertyEditor) {
					Class<?> c = editor.getRange();
					if( Entity.class.isAssignableFrom(c)
						|| Stoichiometry.class.isAssignableFrom(c)
						|| "nextStep".equals(prop) 
						) {	
						pass = false;; 
					}
				} else if("name".equals(prop))
					pass = false;
				
				return pass;
			}
		};
		
		@SuppressWarnings("unchecked")
		AbstractTraverser bpeAutoMapper = new AbstractTraverser(SimpleEditorMap.L3, filter) 
		{
			final Stack<String> propPath = new Stack<String>();

			@SuppressWarnings("rawtypes")
			@Override
			protected void visit(Object obj, BioPAXElement bpe, Model model,
					PropertyEditor editor) 
			{
				if (obj != null && !editor.isUnknown(obj)) {
					propPath.push(editor.getProperty());
					final String attrName = StringUtils.join(propPath, "/");
					String value = obj.toString();
					if (!"".equalsIgnoreCase(value.toString().replaceAll("\\]|\\[", ""))) 
					{
						if (editor.isMultipleCardinality()) {
							CyRow row = network.getRow(node);
							List<String> vals = new ArrayList<String>();
							// consider existing attribute values
							if (row.isSet(attrName)) {
								Class<?> listElementType = row.getTable().getColumn(attrName).getListElementType();
								List prevList = row.getList(attrName, listElementType);
								if(!prevList.contains(value)) prevList.add(value);
							} else { //create
								vals.add(value);
								AttributeUtil.set(network, node, attrName, vals, String.class);
							}
						} else {
							AttributeUtil.set(network, node, attrName, value, String.class);
						}
					}
					
					// currently, we don't map absolutely all BioPAX relationships to edges/attributes: 
					// traverse deeper only if it's an object range property
					// or single cardinality (- otherwise would 
					// result with having too many/branchy Cy attributes)
					if (editor instanceof ObjectPropertyEditor
							&& !editor.isMultipleCardinality()) 
					// this effectively prevents going into details for
					// such objects as values of 'xref', 'memberEntityReference', 
					// 'componentStoichiometry', etc. props.
					{
						traverse((BioPAXElement) obj, null);
					}

					propPath.pop();
				}
			}
		};

		// set the most important attributes
		AttributeUtil.set(network, node, BIOPAX_URI, element.getUri(), String.class);
		AttributeUtil.set(network, node, BIOPAX_ENTITY_TYPE, element.getModelInterface().getSimpleName(), String.class);

		String name = getName(element);
		
		if (!(element instanceof Interaction)) {
			// get chemical modification & cellular location attributes
			NodeAttributesWrapper chemicalModificationsWrapper = getInteractionChemicalModifications(element);
			// set node attributes
			if(chemicalModificationsWrapper != null) {
				// add modifications to the label/name
				name += chemicalModificationsWrapper.toString();
				//set the node attribute (chem. mod. list)
				List<String> list = chemicalModificationsWrapper.asList();
				if (list != null && !list.isEmpty()) {
					// store chemical modifications to be used by the view details panel, node attribute browser, Quick Find
					AttributeUtil.set(network, node, BIOPAX_CHEMICAL_MODIFICATIONS_LIST, list, String.class);
					if (list.contains(PHOSPHORYLATION_SITE)) {
						AttributeUtil.set(network, node, BIOPAX_ENTITY_TYPE, PROTEIN_PHOSPHORYLATED, String.class);
					}
				}
			}

			// add cellular location to the label/name
			if(element instanceof PhysicalEntity) {
				CellularLocationVocabulary cl = ((PhysicalEntity) element).getCellularLocation();
				if(cl != null) {
					String terms = cl.toString();//it's like CellularLocationVocabulary_terms...
					terms =  terms.substring(terms.indexOf("_") + 1).replaceAll("\\[|\\]", "");
					name += (terms.length() > 0) ? ("; " + terms) : "";
				}
			}
		}
		// update the name (also used for node's label and quick find)
		AttributeUtil.set(network, node, CyNetwork.NAME, name, String.class);		
		
		// traverse to create the rest of attr.
		bpeAutoMapper.traverse(element, model);
		
        // create custom (convenience?) attributes, mainly - from xrefs
		createExtraXrefAttributes(element, network, node);
	}

	
	public static <T extends Xref> List<ExternalLink> xrefToExternalLinks(BioPAXElement bpe, Class<T> xrefClass) {
		
		if(bpe instanceof XReferrable) {
			List<ExternalLink> erefs = new ArrayList<ExternalLink>();
			erefs.addAll(extractXrefs(new ClassFilterSet<Xref,T>(
				((XReferrable)bpe).getXref(), xrefClass) ));
			if(bpe instanceof SimplePhysicalEntity && 
				((SimplePhysicalEntity)bpe).getEntityReference() != null)
			{
				erefs.addAll(extractXrefs(new ClassFilterSet<Xref,T>(
					((SimplePhysicalEntity)bpe).getEntityReference().getXref(), xrefClass) ));
			}
			return erefs;
		}
		return new ArrayList<ExternalLink>();
	}


	private static List<ExternalLink> extractXrefs(Collection<? extends Xref> xrefs) {
		List<ExternalLink> dbList = new ArrayList<ExternalLink>();

		for (Xref x: xrefs) {		
			String db = null;
			String id = null;
			String relType = null;
			String title = null;
			String year = null;
			String author = null;
			String url = null;
			String source = null;
			
			db = x.getDb();
			id = x.getId();
			if(x instanceof RelationshipXref) {
				RelationshipTypeVocabulary v = ((RelationshipXref)x).getRelationshipType();
				if(v != null) relType = v.getTerm().toString();
			}
			if(x instanceof PublicationXref) {
				PublicationXref px = (PublicationXref)x;
				author = px.getAuthor().toString();
				title = px.getTitle();
				source = px.getSource().toString();
				url = px.getUrl().toString();
				year = px.getYear() + "";
			}

			if ((db != null) && (id != null)) {
				ExternalLink link = new ExternalLink(db, id);
				link.setAuthor(author);
				link.setRelType(relType);
				link.setTitle(title);
				link.setYear(year);
				link.setSource(source);
				link.setUrl(url);
				dbList.add(link);
			}
		}

		return dbList;
	}

	
	private static String ihopLinks(BioPAXElement bpe) {
		List<String> synList = new ArrayList<String>(getSynonyms(bpe));
		List<ExternalLink> dbList = xrefToExternalLinks(bpe, Xref.class);
		String htmlLink = null;
		
		if (!synList.isEmpty() || !dbList.isEmpty()) {
			htmlLink = ExternalLinkUtil.createIHOPLink(bpe.getModelInterface().getSimpleName(),
					synList, dbList, getOrganismTaxonomyId(bpe));
		}

		return htmlLink;
	}


	/**
	 * Import BioPAX data into a new in-memory model.
	 *
	 * @param in BioPAX data file name.
	 * @return BioPaxUtil new instance (containing the imported BioPAX data)
	 * @throws FileNotFoundException 
	 */
	public static Model read(final InputStream in) throws FileNotFoundException {
		Model model = convertFromOwl(in);
		// immediately convert to BioPAX Level3 model
		if(model != null && BioPAXLevel.L2.equals(model.getLevel())) {
			model = new LevelUpgrader().filter(model);
		}
		
		if(model != null)
			fixDisplayName(model);
		
		return model;
	}
	
	private static Model convertFromOwl(final InputStream stream) {
		final Model[] model = new Model[1];
		final SimpleIOHandler handler = new SimpleIOHandler();
		handler.mergeDuplicates(true); // a workaround (illegal) BioPAX data having duplicated rdf:ID...
		ClassLoaderHack.runWithHack(new Runnable() {
			@Override
			public void run() {
				try {
					model[0] =  handler.convertFromOWL(stream);	
				} catch (Throwable e) {
					log.error("convertFromOwl failed: " + e);
				}
			}
		}, com.ctc.wstx.stax.WstxInputFactory.class);
		return model[0];
	}


	/**
	 * Gets the display name of the node
	 * or URI. 
	 * 
	 * @param bpe BioPAX Element
	 * @return
	 */
	public static String getName(BioPAXElement bpe) {

		String nodeName = null;
		if(bpe instanceof Named)
			nodeName = ((Named)bpe).getDisplayName();

		return (nodeName == null || nodeName.isEmpty())
				? bpe.getUri()
					: StringEscapeUtils.unescapeHtml(nodeName);
	}

	
	/**
	 * Attempts to get the value of any of the BioPAX properties
	 * in the list.
	 * @param bpe BioPAX Element
	 * @param properties BioPAX property names
	 * 
	 * @return the value or null
	 */
	public static Object getValue(BioPAXElement bpe, String... properties) {
		for (String property : properties) {
			try {
				Method method = bpe.getModelInterface().getMethod(
						"get" + property.substring(0, 1).toUpperCase()
								+ property.substring(1).replace('-', '_'));
				Object invoke = method.invoke(bpe);
				if (invoke != null) {
					return invoke;
				}
//				PropertyEditor editor = SimpleEditorMap.L3
//					.getEditorForProperty(property, bpe.getModelInterface());
//				return editor.getValueFromBean(bpe); // is always a Set!
			} catch (Exception e) {
				if(log.isDebugEnabled()) {
					// this is often OK, as we guess L2 or L3 properties...
					log.debug("Ignore property " + property + " for " 
						+ bpe.getUri() + ": " + e);
				}
			}
		}
		return null;
	}

	
	/**
	 * Attempts to get the values of specified BioPAX properties.
	 * @param bpe BioPAX Element
	 * @param properties BioPAX property names
	 * 
	 * @return the set of property values or null
	 */
	public static Collection<?> getValues(BioPAXElement bpe, String... properties) {
		Collection<Object> col = new HashSet<Object>();
		
		for (String property : properties) {
			try {
				Method method = bpe.getModelInterface().getMethod(
						"get" + property.substring(0, 1).toUpperCase()
								+ property.substring(1).replace('-', '_'));
				
				Object invoke = method.invoke(bpe);
				if (invoke != null) {
					// return value can be collection or Object
					if (invoke instanceof Collection) {
						col.addAll((Collection) invoke);
					} else {
						col.add(invoke);
					}
				}
			} catch (Exception e) {
				if(log.isDebugEnabled()) {
					log.debug("Cannot get value of '" + property + "' for "
						+ bpe.getUri() + ": " + e);
				}
			}
		}
		
		return col;
	}


	/**
	 * Gets all names, if any.
	 *
	 * @param bpe BioPAX element
	 * @return Collection of names.
	 */
	public static Collection<String> getSynonyms(BioPAXElement bpe) {
		Collection<String> names = new HashSet<String>();
		if(bpe instanceof Named) {
			names = ((Named)bpe).getName();
		}
		return names;
	}

	
	/**
	 * Gets the NCBI Taxonomy ID.
	 * @param bpe BioPAX element
	 *
	 * @return taxonomyId, or -1, if not available.
	 */
	public static int getOrganismTaxonomyId(BioPAXElement bpe) {
		int taxonomyId = -1;
		
		try {
			Object bs = getValue(bpe, "organism");
			if (bs instanceof BioSource) {
				Set<Xref> xrefs = ((BioSource)bs).getXref();
				if(!xrefs.isEmpty()) {
					Xref tx = xrefs.iterator().next();
					taxonomyId = Integer.parseInt(tx.getId());
				}
			}
		} catch (Exception e) {
			taxonomyId = -1;
		}

		return taxonomyId;
	}

	
	private static <T extends Xref> List<T> getXRefs(BioPAXElement bpe, Class<T> xrefClass, 
			boolean withMembersIfGeneric) {
		if(bpe instanceof XReferrable) {
			List<T> erefs = new ArrayList<T>();
			erefs.addAll(new ClassFilterSet<Xref,T>( ((XReferrable)bpe).getXref(), xrefClass) );
			if(bpe instanceof SimplePhysicalEntity && 
				((SimplePhysicalEntity)bpe).getEntityReference() != null)
			{
				EntityReference entityReference = ((SimplePhysicalEntity)bpe).getEntityReference();
				erefs.addAll(new ClassFilterSet<Xref,T>(entityReference.getXref(), xrefClass) );
				//add xrefs from all member ERs (though, not going into members' members...)
				if(withMembersIfGeneric)
					for(EntityReference memberEntityReference : entityReference.getMemberEntityReference())
						erefs.addAll(new ClassFilterSet<Xref,T>(memberEntityReference.getXref(), xrefClass) );
			} else if(bpe instanceof EntityReference) {
				erefs.addAll(new ClassFilterSet<Xref,T>(((EntityReference)bpe).getXref(), xrefClass) );
				//add xrefs from all member ERs (though, not going into members' members...)
				if(withMembersIfGeneric)
					for(EntityReference memberEntityReference : ((EntityReference)bpe).getMemberEntityReference())
						erefs.addAll(new ClassFilterSet<Xref,T>(memberEntityReference.getXref(), xrefClass) );
			}
			
			return erefs;
		}
		return new ArrayList<T>();
	}

	
	/**
	 * Gets the joint set of all known subclasses of the specified BioPAX types.
	 * 
	 * @param classes BioPAX (PaxTools Model Interfaces) Classes
	 * @return
	 */
	public static Collection<Class<? extends BioPAXElement>> getSubclassNames(Class<? extends BioPAXElement>... classes) {
		Collection<Class<? extends BioPAXElement>> subclasses = new HashSet<Class<? extends BioPAXElement>>();
		
		for (Class<? extends BioPAXElement> c : classes) {
			subclasses.addAll(SimpleEditorMap.L3.getKnownSubClassesOf(c));
		}
		
		return subclasses;
	}


	/**
	 * Creates a name for to the BioPAX model
	 * using its top-level process name(s). 
	 * 
	 * @param model
	 * @return
	 */
	public static String getName(Model model) {		
		StringBuffer modelName = new StringBuffer();
		
		Collection<Pathway> pws = ModelUtils.getRootElements(model, Pathway.class);
		for(Pathway pw: pws) {
				modelName.append(" ").append(getName(pw)); 
		}
		
		if(modelName.length()==0) {
			Collection<Interaction> itrs = ModelUtils.getRootElements(model, Interaction.class);
			for(Interaction it: itrs) {
				modelName.append(" ").append(getName(it));
			}	
		}
		
		if(modelName.length()==0) {
			modelName.append(model.getXmlBase());
		}
		
		String name = modelName.toString().trim();

		return name;
	}

	
	/**
	 * Gets the OWL (RDF/XML) representation
	 * of the BioPAX element.
	 * 
	 * @param bpe
	 * @return
	 */
	public static String toOwl(final BioPAXElement bpe) {
		final StringWriter writer = new StringWriter();
		final SimpleIOHandler simpleExporter = new SimpleIOHandler(BioPAXLevel.L3);
		ClassLoaderHack.runWithHack(new Runnable() {
			@Override
			public void run() {
				try {
					simpleExporter.writeObject(writer, bpe);
				} catch (Exception e) {
					log.error("Failed printing '" + bpe.getUri() + "' to OWL", e);
				}
			}
		}, com.ctc.wstx.stax.WstxInputFactory.class);
		return writer.toString();
	}

	/**
	 * For all Named biopax objects, sets 'displayName'
	 * from other names if it was missing.
	 * 
	 * @param model
	 */
	public static void fixDisplayName(Model model) {
		log.info("Trying to auto-set displayName for all BioPAX elements");
		// where it's null, set to the shortest name if possible
		for (Named e : model.getObjects(Named.class)) {
			if (e.getDisplayName() == null) {
				if (e.getStandardName() != null) {
					e.setDisplayName(e.getStandardName());
				} else if (!e.getName().isEmpty()) {
					String dsp = e.getName().iterator().next();
					for (String name : e.getName()) {
						if (name.length() < dsp.length())
							dsp = name;
					}
					e.setDisplayName(dsp);
				}
			}
		}
		// if required, set PE name to (already fixed) ER's name...
		for(EntityReference er : model.getObjects(EntityReference.class)) {
			for(SimplePhysicalEntity spe : er.getEntityReferenceOf()) {
				if(spe.getDisplayName() == null || spe.getDisplayName().trim().length() == 0) {
					if(er.getDisplayName() != null && er.getDisplayName().trim().length() > 0) {
						spe.setDisplayName(er.getDisplayName());
					}
				}
			}
		}
	}

	/**
	 * Converts a BioPAX Model to the
	 * custom Simple Interactions Format (SIF), where each row
	 * describes an inferred bio interaction using tab-separated columns:
	 * URI1, interaction_type, URI2, data_sources, PMIDs, pathway_names
	 * (the last three columns may contain semicolon-separated multiple values).
	 * 
	 * @param m biopax model
	 * @param sifTypes SIF rules/patterns to use
	 * @param sifOutputStream output stream for the SIF entries
	 * @throws IOException when data cannot be written, etc.
	 */
	public static void convertToCustomSIF(
			Model m,
			SIFType[] sifTypes, //SIF rules/patterns to apply/search
			OutputStream sifOutputStream) throws IOException
	{
		//merge interactions with exactly same properties...
		ModelUtils.mergeEquivalentInteractions(m);
		//some extra normalization to get better conversion results
		ModelUtils.normalizeGenerics(m); //TODO not sure want to apply this...
		for(SimplePhysicalEntity spe : new HashSet<SimplePhysicalEntity>(m.getObjects(SimplePhysicalEntity.class))) {
			ModelUtils.addMissingEntityReference(m, spe);
		}

		//convert to binary interactions
		SIFSearcher sifSearcher = new SIFSearcher(new SimpleIDFetcher(), sifTypes);
		Set<SIFInteraction> binaryInts = sifSearcher.searchSIF(m);
		// write interactions and some of their attributes (publications, datasources, pathways)
		SIFToText stt = new CustomFormat(
				OutputColumn.Type.RESOURCE.name(),
				OutputColumn.Type.PUBMED.name(),
				OutputColumn.Type.PATHWAY.name()
		);

		if (!binaryInts.isEmpty()) {
			List<SIFInteraction> interList = new ArrayList<SIFInteraction>(binaryInts);
			Collections.sort(interList);
			OutputStreamWriter writer = new OutputStreamWriter(sifOutputStream);
//			writer.write("PARTICIPANT_A\tINTERACTION_TYPE\tPARTICIPANT_B\tINTERACTION_DATA_SOURCE\tINTERACTION_PUBMED_ID\tPATHWAY_NAMES");
			for (SIFInteraction inter : interList)
				writer.write(stt.convert(inter)+"\n");
			writer.close();
		}
	}

    /**
     * Converts a BioPAX Model to SBGN format.
     *
     * @param m
     * @param out
     */
    public static void convertToSBGN(final Model m, final OutputStream out) {
    	
		ModelUtils.mergeEquivalentInteractions(m);
    	
		//fails when not using this hack (due to another jaxb library version at runtime...)
//    	ClassLoaderHack.runWithHack(new Runnable() {
//			@Override
//			public void run() {
				//create a sbgn converter: no blacklist; do auto-layout
		try {
			L3ToSBGNPDConverter converter = new L3ToSBGNPDConverter(null, null, true);
			converter.writeSBGN(m, out);
			log.debug("Converter BioPAX to SBGN ML (temporary saved in the java tmpdir)");
		} catch (Throwable t) {
			log.error("BioPAX to SBGN ML converter failed", t);
		}
//			}
//    	}, com.sun.xml.bind.v2.ContextFactory.class);
    }

	private static class NodeAttributesWrapper {
		// map of cellular location or chemical modifications
		private Set<String> attributesSet;

		public NodeAttributesWrapper(Set<String> attributesSet) {
			this.attributesSet = attributesSet;
		}

		public List<String> asList() {
			if(attributesSet != null) {
				List<String> list = new ArrayList<String>(attributesSet);
				list.sort(null);
				return list;
			} else
				return Collections.emptyList();
		}

		@Override
		public String toString() {
			if(attributesSet != null && !attributesSet.isEmpty()) {
				return " -" + StringUtils.join(asList(),",");
			}
			else return "";
		}
	}
}
