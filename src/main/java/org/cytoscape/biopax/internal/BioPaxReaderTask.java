package org.cytoscape.biopax.internal;

/*
 * #%L
 * Cytoscape BioPAX Impl (biopax-impl)
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringEscapeUtils;
import org.biopax.paxtools.model.BioPAXElement;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level3.Entity;
import org.biopax.paxtools.model.level3.EntityReference;
import org.biopax.paxtools.pattern.miner.SIFEnum;
import org.biopax.paxtools.pattern.miner.SIFType;
import org.cytoscape.application.NetworkViewRenderer;
import org.cytoscape.biopax.internal.util.AttributeUtil;
import org.cytoscape.biopax.internal.util.BioPaxReaderError;
import org.cytoscape.biopax.internal.util.VisualStyleUtil;
import org.cytoscape.io.read.CyNetworkReader;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNode;
import org.cytoscape.model.subnetwork.CyRootNetwork;
import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.CyNetworkViewFactory;
import org.cytoscape.work.AbstractTask;
import org.cytoscape.work.ProvidesTitle;
import org.cytoscape.work.TaskMonitor;
import org.cytoscape.work.Tunable;
import org.cytoscape.work.util.ListMultipleSelection;
import org.cytoscape.work.util.ListSingleSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * BioPAX File / InputStream Reader Implementation.
 *
 * @author Ethan Cerami.
 * @author Igor Rodchenkov (re-factoring, using PaxTools API, Cytoscape 3)
 */
public class BioPaxReaderTask extends AbstractTask implements CyNetworkReader {
	
	private static final Logger log = LoggerFactory.getLogger(BioPaxReaderTask.class);
	
	private static final String CREATE_NEW_COLLECTION = "A new network collection";

	private final HashMap<String, CyRootNetwork> nameToRootNetworkMap;
	private final VisualStyleUtil visualStyleUtil;
	private final CyServices cyServices;

	private InputStream stream;
	private String inputName;
	private final Collection<CyNetwork> networks;
	private CyRootNetwork rootNetwork;	
	private CyNetworkReader anotherReader;
	
	/**
	 * BioPAX parsing/converting options.
	 * 
	 * @author rodche
	 *
	 */
	private static enum ReaderMode {
		/**
		 * Default BioPAX to Cytoscape network/view mapping: 
		 * entity objects (sub-classes, including interactions too) 
		 * will be CyNodes interconnected by edges that 
		 * correspond to biopax properties with Entity type domain and range; 
		 * some of dependent utility class objects and simple properties are used to
		 * generate node attributes.
		 */
		DEFAULT("Default"),
		
		/**
		 * BioPAX to SIF, and then to Cytoscape mapping:
		 * first, it converts BioPAX to SIF (using Paxtools library); next, 
		 * delegates network/view creation to the first available SIF anotherReader.
		 */
		SIF("SIF"),
		
		/**
		 * BioPAX to SBGN, and then to Cytoscape network/view mapping:
		 * converts BioPAX to SBGN-ML (using Paxtools library); next, 
		 * delegates network/view creation to the first available SBGN anotherReader,
		 * e.g., CySBGN (when it's available...)
		 */
		SBGN("SBGN");
		
		private final String name;

		ReaderMode(String name) {
			this.name = name;
		}
		
		@Override
		public String toString() {
			return name;
		}
		
		static String[] names() {
			ReaderMode vals[] = ReaderMode.values();
			String names[] = new String[vals.length];
			for(int i= 0; i < vals.length; i++)
				names[i] = vals[i].toString();
			return names;
		}
	}

	
	@ProvidesTitle()
	public String tunableDialogTitle() {
		return "BioPAX Reader Task";
	}
	
	@Tunable(description = "Model Mapping:", groups = {"Options"}, 
			tooltip="<html>Choose how to read BioPAX:" +
					"<ul>" +
					"<li><strong>Default</strong>: map states, interactions to nodes; properties - to edges, attributes;</li>"+
					"<li><strong>SIF</strong>: convert BioPAX to SIF network and attributes;</li>" +
					"<li><strong>SBGN</strong>: convert BioPAX to SBGN, find a SBGN reader, etc.</li>" +
					"</ul></html>"
			, gravity=500, xorChildren=true)
	public ListSingleSelection<ReaderMode> readerMode;
		
	@Tunable(description = "Network Collection:" , groups = {"Options","Default"}, tooltip="Choose a Network Collection", 
			dependsOn="readerMode=Default", gravity=701, xorKey="Default")
	public ListSingleSelection<String> rootNetworkSelection;
	
	@Tunable(description = "Network View Renderer:", groups = {"Options","Default"}, gravity=702, xorKey="Default", dependsOn="readerMode=Default")
	public ListSingleSelection<NetworkViewRenderer> rendererList;

	//select inference rules (multi-selection) for the SIF converter
	@Tunable(description = "Binary interactions to infer:" , groups = {"Options","SIF"}, tooltip="Select inference patterns/rules to search/apply",
			gravity=703, xorKey="SIF", dependsOn = "readerMode=SIF")
	public ListMultipleSelection<SIFType> sifSelection;

	//TODO init SBGN options if required
	@Tunable(description = "SBGN Options:" , groups = {"Options","SBGN"}, tooltip="Currently not available", 
			gravity=704, xorKey="SBGN", dependsOn = "readerMode=SBGN")
	public ListSingleSelection<String> sbgnSelection;
	
	/**
	 * Constructor
	 * 
	 * @param stream input biopax stream
	 * @param inputName a file or pathway name (can be later updated using actual data)
	 * @param cyServices api services
	 * @param visualStyleUtil  biopax/sif visual style utilities
	 */
	public BioPaxReaderTask(InputStream stream, String inputName, 
			CyServices cyServices, VisualStyleUtil visualStyleUtil) 
	{
		this.networks = new HashSet<CyNetwork>();
		this.stream = stream;
		this.inputName = inputName;
		this.cyServices = cyServices;
		this.visualStyleUtil = visualStyleUtil;
		
		// initialize the root networks Collection
		nameToRootNetworkMap = new HashMap<String, CyRootNetwork>();
		for (CyNetwork net : cyServices.networkManager.getNetworkSet()) {
			final CyRootNetwork rootNet = cyServices.rootNetworkManager.getRootNetwork(net);
			if (!nameToRootNetworkMap.containsValue(rootNet))
				nameToRootNetworkMap.put(rootNet.getRow(rootNet).get(CyRootNetwork.NAME, String.class), rootNet);
		}		
		List<String> rootNames = new ArrayList<String>();
		rootNames.add(CREATE_NEW_COLLECTION);
		rootNames.addAll(nameToRootNetworkMap.keySet());
		rootNetworkSelection = new ListSingleSelection<String>(rootNames);
		rootNetworkSelection.setSelectedValue(CREATE_NEW_COLLECTION);

		// initialize the list of data processing modes
		readerMode = new ListSingleSelection<>(ReaderMode.values());
		readerMode.setSelectedValue(ReaderMode.DEFAULT);
		
		// init the SIF rules/patterns list
		sifSelection = new ListMultipleSelection<SIFType>(SIFEnum.values());
		sifSelection.setSelectedValues(sifSelection.getPossibleValues());

		//TODO init SBGN options
		sbgnSelection = new ListSingleSelection<String>();
		
		// initialize renderer list
		final List<NetworkViewRenderer> renderers = new ArrayList<>();
		final Set<NetworkViewRenderer> rendererSet = cyServices.applicationManager.getNetworkViewRendererSet();
		// If there is only one registered renderer, we don't want to add it to the List Selection,
		// so the combo-box does not appear to the user, since there is nothing to select anyway.
		if (rendererSet.size() > 1) {
			renderers.addAll(rendererSet);
			Collections.sort(renderers, new Comparator<NetworkViewRenderer>() {
				@Override
				public int compare(NetworkViewRenderer r1, NetworkViewRenderer r2) {
					return r1.toString().compareToIgnoreCase(r2.toString());
				}
			});
		}
		rendererList = new ListSingleSelection<>(renderers);
	}
	
	@Override
	public void run(TaskMonitor taskMonitor) throws Exception 
	{
		taskMonitor.setTitle("BioPAX reader");
		taskMonitor.setProgress(0.0);
		
		if(cancelled) return;
		
		// import BioPAX data into a new in-memory model
		Model model = null;
		try {
			model = BioPaxMapper.read(stream);
		} catch (Throwable e) {
			throw new BioPaxReaderError("BioPAX reader failed to build a BioPAX model " +
					"(check the data for syntax errors) - " + e);
		}
		
		if(model == null) {
			throw new BioPaxReaderError("BioPAX reader did not find any BioPAX data there.");
		}
		
		final String networkName = getNetworkName(model);
		String msg = "Model " + networkName + " contains " 
				+ model.getObjects().size() + " BioPAX elements";
		log.info(msg);
		taskMonitor.setStatusMessage(msg);
		
		//set parent/root network (can be null - add a new networks group)
		rootNetwork = nameToRootNetworkMap.get(rootNetworkSelection.getSelectedValue());
		
		final BioPaxMapper mapper = new BioPaxMapper(model, cyServices.networkFactory);
			
		ReaderMode selectedMode = readerMode.getSelectedValue();
		switch (selectedMode) {
		case DEFAULT:
			anotherReader = null;
			// Map BioPAX Data to Cytoscape Nodes/Edges (run as task)
			taskMonitor.setStatusMessage("Mapping BioPAX model to CyNetwork...");
			CyNetwork network = mapper.createCyNetwork(networkName, rootNetwork);
			if (network.getNodeCount() == 0)
				throw new BioPaxReaderError("Pathway is empty. Please check the BioPAX source file.");
			// set the biopax network mapping type for other plugins
			AttributeUtil.set(network, network, BioPaxMapper.BIOPAX_NETWORK, "DEFAULT", String.class);
			//(the network name attr. was already set by the biopax mapper)
			
			//register the network
			networks.add(network);
			break;

		case SIF:
//			taskMonitor.setStatusMessage("Normalizing the BioPAX model...");
//			BioPaxMapper.normalize(model);

			//convert BioPAX to the custom binary SIF format (using a tmp file)
			taskMonitor.setStatusMessage("Mapping BioPAX model to SIF, then to " +
					"CyNetwork (using the first discovered SIF reader)...");
			final File tmpSifFile = File.createTempFile("tmp_biopax2sif", ".sif");
			tmpSifFile.deleteOnExit();
			BioPaxMapper.convertToCustomSIF(model,
					sifSelection.getSelectedValues().toArray(new SIFType[]{}),
						new FileOutputStream(tmpSifFile));

			// create a new CyNetwork
			CyNetwork net = (rootNetwork == null)
					? cyServices.networkFactory.createNetwork()
						: rootNetwork.addSubNetwork();

			// line-by-line parse the custom SIF file (- create nodes, edges and edge attributes)
			CustomSifParser customSifParser = new CustomSifParser(net, cyServices);
			BufferedReader reader = Files.newBufferedReader(tmpSifFile.toPath());
			String line = null;
			while((line = reader.readLine()) != null) {
				customSifParser.parse(line);
			}
			reader.close();

			// create node attributes from the BioPAX properties
			createSifNodeAttr(model, net, taskMonitor);

			// final touches -
			// set the biopax network mapping type for other plugins to use/consider
			AttributeUtil.set(net, net, BioPaxMapper.BIOPAX_NETWORK, "SIF", String.class);
			//set the network name (very important!)
			AttributeUtil.set(net, net, CyNetwork.NAME, networkName, String.class);
			//register the network
			networks.add(net);
			taskMonitor.setStatusMessage("SIF network updated...");
			break;

		case SBGN:
			//convert to SBGN
			taskMonitor.setStatusMessage("Mapping BioPAX model to SBGN...");
			File sbgnFile = File.createTempFile("biopax", ".sbgn.xml");
			sbgnFile.deleteOnExit(); 
			BioPaxMapper.convertToSBGN(model, new FileOutputStream(sbgnFile));
			// try to discover a SBGN reader to pass the xml data there
			try {
				anotherReader = cyServices.networkViewReaderManager.getReader(sbgnFile.toURI(), networkName);
			} catch (Throwable t) {
				log.warn("No SBGN reader found or BioPAX-SBGN conversion failed", t.getMessage());
			}
			if(anotherReader != null) {				
				insertTasksAfterCurrentTask(
					anotherReader, 
					new AbstractTask() {
					@Override
					public void run(TaskMonitor taskMonitor) throws Exception {
						taskMonitor.setTitle("BioPAX reader");
						taskMonitor.setStatusMessage("Updating attributess...");
						for (CyNetwork network : anotherReader.getNetworks()) {	
							//TODO create attributes from biopax properties (depends on actual SBGN reader, if any) ?
							// set the biopax network mapping type for other plugins
							AttributeUtil.set(network, network, BioPaxMapper.BIOPAX_NETWORK, "SBGN", String.class);	
							// set the network name attribute!
							AttributeUtil.set(network, network, CyNetwork.NAME, networkName, String.class);
							//register it
							networks.add(network);							
						}
						taskMonitor.setProgress(1.0);
					}
				})
				;
			} else {
				taskMonitor.setStatusMessage("No SBGN ML reader found - no CyNetwork created");
			}
			break;
		default:
			break;
		}
	}

	
	private void createSifNodeAttr(Model model, CyNetwork cyNetwork,
								   TaskMonitor taskMonitor) throws IOException
	{
		taskMonitor.setStatusMessage("Updating SIF network node attributes from the BioPAX model...");

		// Set the Quick Find Default Index
		AttributeUtil.set(cyNetwork, cyNetwork, "quickfind.default_index", CyNetwork.NAME, String.class);
		if (cancelled) return;

		// Set node attributes from the Biopax Model
		for (CyNode node : cyNetwork.getNodeList()) {
			String uri = cyNetwork.getRow(node).get(CyNetwork.NAME, String.class);
			BioPAXElement e = model.getByID(uri);
			if(e instanceof EntityReference || e instanceof Entity) 
			{
				BioPaxMapper.createAttributesFromProperties(e, model, node, cyNetwork);
			} else if (e != null) {
				log.warn("SIF network has an unexpected node: " + uri + " of type " + e.getModelInterface());
				BioPaxMapper.createAttributesFromProperties(e, model, node, cyNetwork);
			} else { //should never happen anymore...
				log.error("(BUG) the biopax model does not have an object with URI=" + uri);
			}
		}
	}


	private String getNetworkName(Model model) {
		// make a network name from pathway name(s) or the file name
		String name = BioPaxMapper.getName(model);
		
		if(name == null || name.trim().isEmpty()) {
			name = (inputName == null || inputName.trim().isEmpty()) 
				? "BioPAX_Network"  : inputName;
		} else {
			int l = (name.length()<100) ? name.length() : 100;
			name = (inputName == null || inputName.trim().isEmpty()) 
				? name.substring(0, l)
				: inputName; //preferred
		}
		
		// Take appropriate adjustments, if name already exists
		name = cyServices.naming.getSuggestedNetworkTitle(
			StringEscapeUtils.unescapeHtml(name) + 
			" (" + readerMode.getSelectedValue() + ")");
		
		log.info("New BioPAX network name is: " + name);
		
		return name;
	}


	@Override
	public CyNetwork[] getNetworks() {
		return networks.toArray(new CyNetwork[] {});
	}

	
	/* Looks, unless called directly, this runs once the view is created 
	 * for the first time, i.e., after the network is imported from a biopax file/stream 
	 * (so it's up to the user or another app. then to apply custom style/layout to 
	 * new view, should the first one is destroyed and new one created.
	 */
	@Override
	public CyNetworkView buildCyNetworkView(final CyNetwork network) {
		CyNetworkView view;		
		//visual style depends on the tunable
		ReaderMode currentMode = readerMode.getSelectedValue();
		switch (currentMode) {
		case DEFAULT:
			view = getNetworkViewFactory().createNetworkView(network);
			break;
		case SIF:
			view = getNetworkViewFactory().createNetworkView(network);
			break;
		case SBGN: //works if there is some SBGN reader app registered in Cy3
		default:
			view = anotherReader.buildCyNetworkView(network);
			//TODO: a layout for SBGN views (if not already done)? 
			break;
		}

		if(!cyServices.networkViewManager.getNetworkViews(network).contains(view))
			cyServices.networkViewManager.addNetworkView(view);
		
		return view;
	}

	private CyNetworkViewFactory getNetworkViewFactory() {
		if (rendererList != null && rendererList.getSelectedValue() != null)
			return rendererList.getSelectedValue().getNetworkViewFactory();
		
		return cyServices.networkViewFactory;
	}
}
